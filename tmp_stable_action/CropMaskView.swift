//
//  CropMaskView.swift
//  Stable Action
//
//  A pure viewfinder border overlay — no masks, no black fill.
//  Sits on top of a camera preview that is already clipped to a 3:4
//  portrait rectangle. Draws a thin border + corner brackets that
//  exactly hug the edges of its container.
//

import SwiftUI

struct CropMaskView: View {

    var body: some View {
        // The view is placed inside a container that is already 3:4.
        // We just draw the border + corners to frame that exact area.
        ZStack {
            // Thin border tracing the viewport edge
            Rectangle()
                .strokeBorder(Color.white.opacity(0.45), lineWidth: 1)

            // Yellow corner brackets
            ViewfinderBrackets()
                .stroke(
                    Color.yellow.opacity(0.9),
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round)
                )
        }
        .allowsHitTesting(false)
    }
}

// Four L-shaped corner brackets that fill the bounds of the view exactly
private struct ViewfinderBrackets: Shape {
    private let arm: CGFloat = 22

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let a = arm
        // Inset by 1 pt so the brackets sit just inside the border line
        let r = rect.insetBy(dx: 1, dy: 1)

        // Top-left
        p.move(to: CGPoint(x: r.minX, y: r.minY + a))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX + a, y: r.minY))

        // Top-right
        p.move(to: CGPoint(x: r.maxX - a, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY + a))

        // Bottom-right
        p.move(to: CGPoint(x: r.maxX, y: r.maxY - a))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX - a, y: r.maxY))

        // Bottom-left
        p.move(to: CGPoint(x: r.minX + a, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY - a))

        return p
    }
}
