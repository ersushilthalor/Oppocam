//
//  LiquidGlassActionToggle.swift
//  Stable Action
//
//  Created by Rudra Shah on 26/02/26.
//

import SwiftUI

/// iOS-26-style liquid-glass pill toggle for Action Mode.
/// Two labels sit inside a frosted pill; a white capsule slides beneath the active one.
struct LiquidGlassActionToggle: View {
    @Binding var isOn: Bool

    private let pillHeight: CGFloat = 38
    private let horizontalPad: CGFloat = 6
    private let labelSpacing: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            let totalWidth = geo.size.width
            let halfWidth = totalWidth / 2

            ZStack(alignment: .leading) {

                // ── Frosted glass pill background ──────────────────────────
                Capsule()
                    .fill(.ultraThinMaterial)
                    .overlay(
                        Capsule()
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.55), .white.opacity(0.10)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.8
                            )
                    )
                    .shadow(color: .black.opacity(0.25), radius: 8, y: 4)

                // ── Glowing "liquid" highlight on the active side ──────────
                Capsule()
                    .fill(
                        RadialGradient(
                            colors: [.white.opacity(0.18), .clear],
                            center: isOn ? .topTrailing : .topLeading,
                            startRadius: 0,
                            endRadius: halfWidth * 1.2
                        )
                    )
                    .animation(.spring(response: 0.4, dampingFraction: 0.72), value: isOn)

                // ── Sliding white capsule knob ─────────────────────────────
                Capsule()
                    .fill(.white)
                    .frame(width: halfWidth - horizontalPad, height: pillHeight - horizontalPad * 2)
                    .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
                    .offset(x: isOn ? halfWidth + horizontalPad / 2 : horizontalPad / 2)
                    .animation(.spring(response: 0.38, dampingFraction: 0.68), value: isOn)

                // ── Labels ─────────────────────────────────────────────────
                HStack(spacing: labelSpacing) {
                    label(title: "Normal", icon: "video.fill", active: !isOn, width: halfWidth)
                    label(title: "Action",  icon: "bolt.fill",  active: isOn,  width: halfWidth)
                }
            }
            .frame(height: pillHeight)
            .contentShape(Capsule())
            .gesture(
                DragGesture(minimumDistance: 4)
                    .onEnded { value in
                        let newState = value.location.x > totalWidth / 2
                        if newState != isOn {
                            let impact = UIImpactFeedbackGenerator(style: .medium)
                            impact.impactOccurred()
                            withAnimation { isOn = newState }
                        }
                    }
            )
            .onTapGesture { location in
                let newState = location.x > totalWidth / 2
                if newState != isOn {
                    let impact = UIImpactFeedbackGenerator(style: .medium)
                    impact.impactOccurred()
                    withAnimation { isOn = newState }
                }
            }
        }
        .frame(height: pillHeight)
    }

    @ViewBuilder
    private func label(title: String, icon: String, active: Bool, width: CGFloat) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(size: 11, weight: .semibold))
            Text(title)
                .font(.system(size: 13, weight: .semibold, design: .rounded))
        }
        .foregroundStyle(active ? Color.black : Color.white)
        .frame(width: width)
        .animation(.spring(response: 0.38, dampingFraction: 0.68), value: active)
    }
}

#Preview {
    struct PreviewWrapper: View {
        @State private var on = false
        var body: some View {
            ZStack {
                Color.black.ignoresSafeArea()
                LiquidGlassActionToggle(isOn: $on)
                    .padding(.horizontal, 40)
            }
        }
    }
    return PreviewWrapper()
}
