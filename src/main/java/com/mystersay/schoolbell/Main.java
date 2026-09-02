package com.mystersay.schoolbell;

import javax.swing.*;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Theme.install();
        SwingUtilities.invokeLater(() -> {
            ScheduleStore store = new ScheduleStore();
            AppSettings settings = store.load();
            MainFrame frame = new MainFrame(settings, store);
            frame.setVisible(true);
        });
    }
}
