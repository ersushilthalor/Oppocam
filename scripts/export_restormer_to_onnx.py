#!/usr/bin/env python3
"""
Official Restormer PyTorch (.pth) to ONNX Converter
Based on the official CVPR 2022 Restormer repository: https://github.com/swz30/Restormer

This script converts official PyTorch pretrained checkpoints (.pth)
into standard Open Neural Network Exchange (.onnx) models compatible with
the Android Camera Pro Restormer AI Model Manager.

Usage:
    python3 scripts/export_restormer_to_onnx.py \\
        --checkpoint path/to/deraining.pth \\
        --task deraining \\
        --output restormer_derain.onnx \\
        --tile_size 128

Prerequisites:
    pip install torch torchvision onnx onnxruntime
"""

import argparse
import os
import sys
import numbers
import torch
import torch.nn as nn
import torch.nn.functional as F

##########################################################################
## Layer Norm & Helper Modules (Official Restormer Implementation)
##########################################################################

def to_3d(x):
    return rearrange(x, 'b c h w -> b (h w) c')

def to_4d(x, h, w):
    return rearrange(x, 'b (h w) c -> b c h w', h=h, w=w)

class BiasFree_LayerNorm(nn.Module):
    def __init__(self, normalized_shape):
        super(BiasFree_LayerNorm, self).__init__()
        if isinstance(normalized_shape, numbers.Integral):
            normalized_shape = (normalized_shape,)
        self.normalized_shape = torch.Size(normalized_shape)
        self.weight = nn.Parameter(torch.ones(self.normalized_shape))

    def forward(self, x):
        sigma = x.var(-1, keepdim=True, unbiased=False)
        return x / torch.sqrt(sigma + 1e-5) * self.weight

class WithBias_LayerNorm(nn.Module):
    def __init__(self, normalized_shape):
        super(WithBias_LayerNorm, self).__init__()
        if isinstance(normalized_shape, numbers.Integral):
            normalized_shape = (normalized_shape,)
        self.normalized_shape = torch.Size(normalized_shape)
        self.weight = nn.Parameter(torch.ones(self.normalized_shape))
        self.bias = nn.Parameter(torch.zeros(self.normalized_shape))

    def forward(self, x):
        mu = x.mean(-1, keepdim=True)
        sigma = x.var(-1, keepdim=True, unbiased=False)
        return (x - mu) / torch.sqrt(sigma + 1e-5) * self.weight + self.bias

class LayerNorm(nn.Module):
    def __init__(self, dim, LayerNorm_type='WithBias'):
        super(LayerNorm, self).__init__()
        if LayerNorm_type == 'BiasFree':
            self.body = BiasFree_LayerNorm(dim)
        else:
            self.body = WithBias_LayerNorm(dim)

    def forward(self, x):
        # x is [B, C, H, W]
        h, w = x.shape[-2:]
        # Convert to [B, H*W, C] for channels-last LayerNorm
        x_perm = x.permute(0, 2, 3, 1).contiguous()
        out = self.body(x_perm)
        return out.permute(0, 3, 1, 2).contiguous()

##########################################################################
## Multi-Dconv Head Transposed Attention (MDTA)
##########################################################################

class Attention(nn.Module):
    def __init__(self, dim, num_heads, bias):
        super(Attention, self).__init__()
        self.num_heads = num_heads
        self.temperature = nn.Parameter(torch.ones(num_heads, 1, 1))

        self.qkv = nn.Conv2d(dim, dim * 3, kernel_size=1, bias=bias)
        self.qkv_dwconv = nn.Conv2d(dim * 3, dim * 3, kernel_size=3, stride=1, padding=1, groups=dim * 3, bias=bias)
        self.project_out = nn.Conv2d(dim, dim, kernel_size=1, bias=bias)

    def forward(self, x):
        b, c, h, w = x.shape
        qkv = self.qkv_dwconv(self.qkv(x))
        q, k, v = qkv.chunk(3, dim=1)

        q = q.reshape(b, self.num_heads, -1, h * w)
        k = k.reshape(b, self.num_heads, -1, h * w)
        v = v.reshape(b, self.num_heads, -1, h * w)

        q = torch.nn.functional.normalize(q, dim=-1)
        k = torch.nn.functional.normalize(k, dim=-1)

        attn = (q @ k.transpose(-2, -1)) * self.temperature
        attn = attn.softmax(dim=-1)

        out = (attn @ v)
        out = out.reshape(b, -1, h, w)
        out = self.project_out(out)
        return out

##########################################################################
## Gated-Dconv Feed-Forward Network (GDFN)
##########################################################################

class FeedForward(nn.Module):
    def __init__(self, dim, ffn_expansion_factor, bias):
        super(FeedForward, self).__init__()
        hidden_features = int(dim * ffn_expansion_factor)
        self.project_in = nn.Conv2d(dim, hidden_features * 2, kernel_size=1, bias=bias)
        self.dwconv = nn.Conv2d(hidden_features * 2, hidden_features * 2, kernel_size=3, stride=1, padding=1, groups=hidden_features * 2, bias=bias)
        self.project_out = nn.Conv2d(hidden_features, dim, kernel_size=1, bias=bias)

    def forward(self, x):
        x = self.project_in(x)
        x1, x2 = self.dwconv(x).chunk(2, dim=1)
        x = F.gelu(x1) * x2
        x = self.project_out(x)
        return x

##########################################################################
## Transformer Block
##########################################################################

class TransformerBlock(nn.Module):
    def __init__(self, dim, num_heads, ffn_expansion_factor, bias, LayerNorm_type):
        super(TransformerBlock, self).__init__()
        self.norm1 = LayerNorm(dim, LayerNorm_type)
        self.attn = Attention(dim, num_heads, bias)
        self.norm2 = LayerNorm(dim, LayerNorm_type)
        self.ffn = FeedForward(dim, ffn_expansion_factor, bias)

    def forward(self, x):
        x = x + self.attn(self.norm1(x))
        x = x + self.ffn(self.norm2(x))
        return x

##########################################################################
## DownSample & UpSample
##########################################################################

class DownSample(nn.Module):
    def __init__(self, in_channels, scale_factor=2):
        super(DownSample, self).__init__()
        self.scale_factor = scale_factor
        self.body = nn.Sequential(
            nn.Conv2d(in_channels, in_channels * scale_factor, kernel_size=3, stride=1, padding=1, bias=False)
        )

    def forward(self, x):
        # PixelUnshuffle equivalent via PixelShuffle inverse or strided reshape
        b, c, h, w = x.shape
        r = self.scale_factor
        out = x.reshape(b, c, h // r, r, w // r, r).permute(0, 1, 3, 5, 2, 4).reshape(b, c * (r * r), h // r, w // r)
        return self.body(out)

class UpSample(nn.Module):
    def __init__(self, in_channels, scale_factor=2):
        super(UpSample, self).__init__()
        self.scale_factor = scale_factor
        self.body = nn.Sequential(
            nn.Conv2d(in_channels, in_channels // scale_factor, kernel_size=3, stride=1, padding=1, bias=False)
        )

    def forward(self, x):
        b, c, h, w = x.shape
        r = self.scale_factor
        # PixelShuffle equivalent
        out = x.reshape(b, c // (r * r), r, r, h, w).permute(0, 1, 4, 2, 5, 3).reshape(b, c // (r * r), h * r, w * r)
        return self.body(out)

##########################################################################
## Full Restormer Network
##########################################################################

class Restormer(nn.Module):
    def __init__(self, 
        inp_channels=3, 
        out_channels=3, 
        dim=48,
        num_blocks=[4, 6, 6, 8], 
        num_refinement_blocks=4,
        heads=[1, 2, 4, 8],
        ffn_expansion_factor=2.66,
        bias=False,
        LayerNorm_type='WithBias',
        dual_pixel_task=False
    ):
        super(Restormer, self).__init__()

        self.patch_embed = nn.Conv2d(inp_channels, dim, kernel_size=3, padding=1, bias=bias)

        self.encoder_level1 = nn.Sequential(*[TransformerBlock(dim=dim, num_heads=heads[0], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_blocks[0])])
        
        self.down1_2 = DownSample(dim)
        self.encoder_level2 = nn.Sequential(*[TransformerBlock(dim=int(dim*2**1), num_heads=heads[1], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_blocks[1])])
        
        self.down2_3 = DownSample(int(dim*2**1))
        self.encoder_level3 = nn.Sequential(*[TransformerBlock(dim=int(dim*2**2), num_heads=heads[2], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_blocks[2])])

        self.down3_4 = DownSample(int(dim*2**2))
        self.latent = nn.Sequential(*[TransformerBlock(dim=int(dim*2**3), num_heads=heads[3], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_blocks[3])])
        
        self.up4_3 = UpSample(int(dim*2**3))
        self.reduce_chan_level3 = nn.Conv2d(int(dim*2**3), int(dim*2**2), kernel_size=1, bias=bias)
        self.decoder_level3 = nn.Sequential(*[TransformerBlock(dim=int(dim*2**2), num_heads=heads[2], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_blocks[2])])

        self.up3_2 = UpSample(int(dim*2**2))
        self.reduce_chan_level2 = nn.Conv2d(int(dim*2**2), int(dim*2**1), kernel_size=1, bias=bias)
        self.decoder_level2 = nn.Sequential(*[TransformerBlock(dim=int(dim*2**1), num_heads=heads[1], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_blocks[1])])
        
        self.up2_1 = UpSample(int(dim*2**1))
        self.decoder_level1 = nn.Sequential(*[TransformerBlock(dim=int(dim*2**1), num_heads=heads[0], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_blocks[0])])
        
        self.refinement = nn.Sequential(*[TransformerBlock(dim=int(dim*2**1), num_heads=heads[0], ffn_expansion_factor=ffn_expansion_factor, bias=bias, LayerNorm_type=LayerNorm_type) for _ in range(num_refinement_blocks)])
        
        self.output = nn.Conv2d(int(dim*2**1), out_channels, kernel_size=3, padding=1, bias=bias)

    def forward(self, inp_img):
        inp_enc_level1 = self.patch_embed(inp_img)
        out_enc_level1 = self.encoder_level1(inp_enc_level1)
        
        inp_enc_level2 = self.down1_2(out_enc_level1)
        out_enc_level2 = self.encoder_level2(inp_enc_level2)

        inp_enc_level3 = self.down2_3(out_enc_level2)
        out_enc_level3 = self.encoder_level3(inp_enc_level3) 

        inp_enc_level4 = self.down3_4(out_enc_level3)        
        latent = self.latent(inp_enc_level4) 
                        
        inp_dec_level3 = self.up4_3(latent)
        inp_dec_level3 = torch.cat([inp_dec_level3, out_enc_level3], 1)
        inp_dec_level3 = self.reduce_chan_level3(inp_dec_level3)
        out_dec_level3 = self.decoder_level3(inp_dec_level3) 

        inp_dec_level2 = self.up3_2(out_dec_level3)
        inp_dec_level2 = torch.cat([inp_dec_level2, out_enc_level2], 1)
        inp_dec_level2 = self.reduce_chan_level2(inp_dec_level2)
        out_dec_level2 = self.decoder_level2(inp_dec_level2) 

        inp_dec_level1 = self.up2_1(out_dec_level2)
        inp_dec_level1 = torch.cat([inp_dec_level1, out_enc_level1], 1)
        out_dec_level1 = self.decoder_level1(inp_dec_level1)
        
        out_dec_level1 = self.refinement(out_dec_level1)

        out_dec_level1 = self.output(out_dec_level1) + inp_img
        return out_dec_level1


def get_task_parameters(task_name):
    """Returns official configuration parameters for standard Restormer tasks."""
    t = task_name.lower()
    if 'derain' in t:
        return {'dim': 48, 'num_blocks': [4, 6, 6, 8], 'heads': [1, 2, 4, 8], 'num_refinement_blocks': 4}
    elif 'motion' in t or 'gopro' in t:
        return {'dim': 48, 'num_blocks': [4, 6, 6, 8], 'heads': [1, 2, 4, 8], 'num_refinement_blocks': 4}
    elif 'defocus' in t or 'dpdd' in t:
        return {'dim': 48, 'num_blocks': [4, 6, 6, 8], 'heads': [1, 2, 4, 8], 'num_refinement_blocks': 4}
    elif 'denoise' in t or 'sidd' in t or 'dnd' in t:
        return {'dim': 48, 'num_blocks': [4, 6, 6, 8], 'heads': [1, 2, 4, 8], 'num_refinement_blocks': 4}
    elif 'mobile' in t:
        return {'dim': 24, 'num_blocks': [1, 1, 1, 2], 'heads': [1, 2, 4, 8], 'num_refinement_blocks': 2}
    else:
        return {'dim': 48, 'num_blocks': [4, 6, 6, 8], 'heads': [1, 2, 4, 8], 'num_refinement_blocks': 4}


def main():
    parser = argparse.ArgumentParser(description="Convert official Restormer .pth checkpoints to mobile ONNX")
    parser.add_argument("--checkpoint", type=str, required=True, help="Path to official .pth checkpoint file")
    parser.add_argument("--task", type=str, default="deraining", help="Task: deraining, real_denoising, motion_deblurring, defocus_deblurring, or mobile")
    parser.add_argument("--output", type=str, default="restormer_converted.onnx", help="Output .onnx filename")
    parser.add_argument("--tile_size", type=int, default=128, help="Tile resolution for mobile inference (default: 128)")
    parser.add_argument("--dim", type=int, default=None, help="Base channel dimension override (e.g. 48 or 24)")
    args = parser.parse_args()

    if not os.path.exists(args.checkpoint):
        print(f"Error: Checkpoint file '{args.checkpoint}' not found.")
        sys.exit(1)

    print(f"[1/4] Loading checkpoint from: {args.checkpoint}")
    checkpoint = torch.load(args.checkpoint, map_location='cpu')

    if isinstance(checkpoint, dict):
        if 'params' in checkpoint:
            state_dict = checkpoint['params']
        elif 'params_ema' in checkpoint:
            state_dict = checkpoint['params_ema']
        elif 'state_dict' in checkpoint:
            state_dict = checkpoint['state_dict']
        else:
            state_dict = checkpoint
    else:
        state_dict = checkpoint

    params = get_task_parameters(args.task)
    if args.dim is not None:
        params['dim'] = args.dim

    print(f"[2/4] Initializing Restormer architecture (dim={params['dim']}, blocks={params['num_blocks']}, heads={params['heads']})")
    model = Restormer(
        dim=params['dim'],
        num_blocks=params['num_blocks'],
        heads=params['heads'],
        num_refinement_blocks=params['num_refinement_blocks']
    )

    # Clean keys if prefixed with module.
    clean_dict = {}
    for k, v in state_dict.items():
        name = k[7:] if k.startswith('module.') else k
        clean_dict[name] = v

    try:
        model.load_state_dict(clean_dict, strict=True)
        print("Successfully loaded all weights with strict=True.")
    except Exception as e:
        print(f"Warning: Strict weight load failed ({e}). Attempting non-strict load...")
        model.load_state_dict(clean_dict, strict=False)

    model.eval()

    print(f"[3/4] Exporting to ONNX format at tile size {args.tile_size}x{args.tile_size}...")
    dummy_input = torch.randn(1, 3, args.tile_size, args.tile_size)

    torch.onnx.export(
        model,
        dummy_input,
        args.output,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={
            'input': {2: 'height', 3: 'width'},
            'output': {2: 'height', 3: 'width'}
        }
    )

    print(f"[4/4] Verifying exported ONNX model...")
    try:
        import onnx
        onnx_model = onnx.load(args.output)
        onnx.checker.check_model(onnx_model)
        print("ONNX model verification succeeded.")
    except ImportError:
        print("Notice: 'onnx' Python package not installed, skipping onnx.checker.")

    file_size_mb = os.path.getsize(args.output) / (1024 * 1024)
    print("=" * 60)
    print(f"SUCCESS: Exported Restormer ONNX model: {args.output} ({file_size_mb:.2f} MB)")
    print("=" * 60)
    print("Next steps to use this model in the Android Camera app:")
    print("1. Transfer the .onnx file to your Android device (Downloads or Internal Storage).")
    print("2. In the Camera app, tap MORE -> Restormer AI Studio (or Settings -> Restormer AI).")
    print("3. Tap 'Import Model (.onnx / .tflite)' and select this file.")
    print("4. Tap 'Verify Model Inference Now' to execute the diagnostic benchmark.")

if __name__ == '__main__':
    main()
