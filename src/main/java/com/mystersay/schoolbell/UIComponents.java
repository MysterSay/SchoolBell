package com.mystersay.schoolbell;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

public final class UIComponents {
    private UIComponents() {}

    public static final class RoundedPanel extends JPanel {
        private final int radius;
        private Color fill;
        private Color stroke;

        public RoundedPanel(LayoutManager layout) {
            this(layout, 22, Theme.CARD, Theme.BORDER);
        }

        public RoundedPanel(LayoutManager layout, int radius, Color fill, Color stroke) {
            super(layout);
            this.radius = radius;
            this.fill = fill;
            this.stroke = stroke;
            setOpaque(false);
        }

        public void setFill(Color fill) {
            this.fill = fill;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 1;
            int h = getHeight() - 1;
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, radius, radius));
            if (stroke != null) {
                g2.setColor(stroke);
                g2.draw(new RoundRectangle2D.Float(.5f, .5f, w - 1, h - 1, radius, radius));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static class ModernButton extends JButton {
        private final int radius;
        private Color normal;
        private Color hover;
        private Color pressed;
        private boolean mouseOver;
        private boolean mousePressed;

        public ModernButton(String text) {
            this(text, Theme.FIELD, Theme.HOVER, Theme.ACCENT_2, 14);
        }

        public ModernButton(String text, Color normal, Color hover, Color pressed, int radius) {
            super(text);
            this.normal = normal;
            this.hover = hover;
            this.pressed = pressed;
            this.radius = radius;
            setForeground(Color.WHITE);
            setFont(Theme.preferredFont(14f, Font.BOLD));
            setBorder(new EmptyBorder(10, 16, 10, 16));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { mouseOver = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { mouseOver = false; mousePressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { mousePressed = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { mousePressed = false; repaint(); }
            });
        }

        public void setPalette(Color normal, Color hover, Color pressed) {
            this.normal = normal;
            this.hover = hover;
            this.pressed = pressed;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = !isEnabled() ? Theme.CARD_2 : mousePressed ? pressed : mouseOver ? hover : normal;
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.setColor(Theme.BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static final class NavButton extends ModernButton {
        private boolean selected;

        public NavButton(String text) {
            super(text, Theme.SIDEBAR, Theme.HOVER, Theme.CARD_2, 14);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(Theme.preferredFont(15f, Font.BOLD));
            setBorder(new EmptyBorder(13, 16, 13, 16));
        }

        public void setSelectedState(boolean selected) {
            this.selected = selected;
            setPalette(selected ? Theme.ACCENT_2 : Theme.SIDEBAR,
                    selected ? Theme.ACCENT : Theme.HOVER,
                    Theme.ACCENT_2);
        }

        public boolean isSelectedState() { return selected; }
    }

    public static final class RoundBellButton extends JButton {
        private boolean hover;
        private boolean pressed;

        public RoundBellButton() {
            super("");
            setPreferredSize(new Dimension(230, 230));
            setMinimumSize(new Dimension(230, 230));
            setMaximumSize(new Dimension(230, 230));
            setForeground(Color.WHITE);
            setFont(Theme.preferredFont(20f, Font.BOLD));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setVerticalTextPosition(SwingConstants.BOTTOM);
            setHorizontalTextPosition(SwingConstants.CENTER);
            setIconTextGap(12);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            });
        }

        @Override
        public boolean contains(int x, int y) {
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;
            double r = Math.min(getWidth(), getHeight()) / 2.0;
            return Math.pow(x - cx, 2) + Math.pow(y - cy, 2) <= r * r;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()) - 8;
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;

            Color outer = pressed ? Theme.ACCENT_2 : hover ? Theme.ACCENT : new Color(61, 78, 170);
            Color inner = pressed ? new Color(53, 67, 145) : hover ? new Color(67, 84, 185) : new Color(48, 58, 116);
            g2.setColor(new Color(99, 126, 255, hover ? 75 : 38));
            g2.fill(new Ellipse2D.Float(x - 4, y - 4, size + 8, size + 8));
            g2.setColor(outer);
            g2.fill(new Ellipse2D.Float(x, y, size, size));
            g2.setColor(inner);
            int inset = 12;
            g2.fill(new Ellipse2D.Float(x + inset, y + inset, size - inset * 2, size - inset * 2));

            // Простий векторний дзвінок, без залежності від emoji-шрифтів.
            int cx = getWidth() / 2;
            int cy = getHeight() / 2 - 28;
            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(Color.WHITE);
            int bellW = 58;
            int bellH = 55;
            g2.drawArc(cx - bellW / 2, cy - 20, bellW, bellH, 20, 140);
            g2.drawLine(cx - 27, cy + 18, cx - 34, cy + 34);
            g2.drawLine(cx + 27, cy + 18, cx + 34, cy + 34);
            g2.drawLine(cx - 34, cy + 34, cx + 34, cy + 34);
            g2.fillOval(cx - 7, cy + 38, 14, 14);

            String label = "ДЗВОНИТИ";
            g2.setFont(Theme.preferredFont(19f, Font.BOLD));
            FontMetrics fm = g2.getFontMetrics();
            int tx = cx - fm.stringWidth(label) / 2;
            int ty = cy + 82;
            g2.drawString(label, tx, ty);
            g2.dispose();

            super.paintComponent(g);
        }
    }
}
