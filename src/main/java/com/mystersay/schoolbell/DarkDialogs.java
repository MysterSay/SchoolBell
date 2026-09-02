package com.mystersay.schoolbell;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class DarkDialogs {
    private DarkDialogs() {}

    public static void message(Window owner, String title, String text) {
        JDialog dialog = baseDialog(owner, title);
        JPanel content = new JPanel(new BorderLayout(0, 18));
        content.setBackground(Theme.BG);
        content.setBorder(new EmptyBorder(22, 24, 20, 24));
        JLabel label = htmlLabel(text);
        content.add(label, BorderLayout.CENTER);
        UIComponents.ModernButton ok = new UIComponents.ModernButton(
                "Гаразд", Theme.ACCENT_2, Theme.ACCENT, Theme.ACCENT_2, 14);
        ok.addActionListener(e -> dialog.dispose());
        JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(ok);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.add(content, BorderLayout.CENTER);
        finish(dialog, owner, 470, 180);
    }

    public static boolean confirm(Window owner, String title, String text) {
        JDialog dialog = baseDialog(owner, title);
        final boolean[] answer = {false};
        JPanel content = new JPanel(new BorderLayout(0, 18));
        content.setBackground(Theme.BG);
        content.setBorder(new EmptyBorder(22, 24, 20, 24));
        content.add(htmlLabel(text), BorderLayout.CENTER);

        UIComponents.ModernButton no = new UIComponents.ModernButton("Скасувати");
        UIComponents.ModernButton yes = new UIComponents.ModernButton(
                "Підтвердити", Theme.DANGER, new Color(205, 78, 91), Theme.DANGER, 14);
        no.addActionListener(e -> dialog.dispose());
        yes.addActionListener(e -> { answer[0] = true; dialog.dispose(); });
        JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.add(no);
        buttons.add(yes);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.add(content, BorderLayout.CENTER);
        finish(dialog, owner, 500, 190);
        return answer[0];
    }

    public static Path chooseAudio(Window owner, String title, Path current) {
        JDialog dialog = baseDialog(owner, title);
        AtomicReference<Path> selected = new AtomicReference<>();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Аудіо (WAV, MP3, FLAC, OGG, OPUS, M4A, AAC, WMA, AIFF, AU, MIDI)", "wav", "wave", "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wma", "aif", "aiff", "au", "mid", "midi"));
        if (current != null && Files.exists(current)) chooser.setSelectedFile(current.toFile());
        Theme.forceDark(chooser);
        chooser.addActionListener(e -> {
            if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand()) && chooser.getSelectedFile() != null) {
                selected.set(chooser.getSelectedFile().toPath().toAbsolutePath());
                dialog.dispose();
            } else if (JFileChooser.CANCEL_SELECTION.equals(e.getActionCommand())) {
                dialog.dispose();
            }
        });
        dialog.add(chooser, BorderLayout.CENTER);
        finish(dialog, owner, 820, 570);
        return selected.get();
    }

    private static JDialog baseDialog(Window owner, String title) {
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(Theme.BG);
        dialog.getRootPane().setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        dialog.add(titleBar(dialog, title), BorderLayout.NORTH);
        return dialog;
    }

    private static JPanel titleBar(JDialog dialog, String title) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.SIDEBAR);
        bar.setBorder(new EmptyBorder(8, 14, 8, 8));
        JLabel label = new JLabel(title);
        label.setForeground(Color.WHITE);
        label.setFont(Theme.preferredFont(13f, Font.BOLD));
        bar.add(label, BorderLayout.WEST);
        UIComponents.ModernButton close = new UIComponents.ModernButton("×", Theme.SIDEBAR, Theme.DANGER, Theme.DANGER, 10);
        close.setFont(Theme.preferredFont(18f, Font.BOLD));
        close.setBorder(new EmptyBorder(4, 12, 4, 12));
        close.addActionListener(e -> dialog.dispose());
        bar.add(close, BorderLayout.EAST);
        installDrag(bar, dialog);
        installDrag(label, dialog);
        return bar;
    }

    private static void installDrag(Component component, Window window) {
        MouseAdapter adapter = new MouseAdapter() {
            Point click;
            @Override public void mousePressed(MouseEvent e) { click = e.getLocationOnScreen(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (click == null) return;
                Point now = e.getLocationOnScreen();
                Point loc = window.getLocation();
                window.setLocation(loc.x + now.x - click.x, loc.y + now.y - click.y);
                click = now;
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }

    private static JLabel htmlLabel(String text) {
        String escaped = text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        JLabel label = new JLabel("<html><div style='width:410px'>" + escaped + "</div></html>");
        label.setForeground(Color.WHITE);
        label.setFont(Theme.preferredFont(14f, Font.PLAIN));
        return label;
    }

    private static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static void finish(JDialog dialog, Window owner, int width, int height) {
        dialog.setSize(width, height);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
