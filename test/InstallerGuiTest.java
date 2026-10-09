/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

import dev.xavier.stein.installer.InstallerApp;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Walks through the installer window without a mouse: opens the installer in the same process,
 * clicks through Swing (doClick) on the language, on Continue and on the main button (Install or
 * Update), waits for the end and saves the window as a PNG at each step. The user's mouse is not
 * touched, and the folder is a test one (--mc).
 *
 * <p>Usage: runtime/bin/java -cp app/stein-installer.jar test/InstallerGuiTest.java &lt;test
 * .minecraft folder&gt; &lt;English|Español|Português&gt; &lt;PNG prefix&gt;
 */
public class InstallerGuiTest {

    public static void main(String[] a) throws Exception {
        String mc = a[0];
        String lang = a[1];
        String out = a[2];
        InstallerApp.main(new String[] {"--mc", mc});
        JFrame frame =
                waitFor(
                        () -> {
                            for (Frame f : Frame.getFrames()) {
                                if (f instanceof JFrame j && f.isShowing()) {
                                    return j;
                                }
                            }
                            return null;
                        },
                        15);
        snapshot(frame, out + "-1-language.png");

        click(frame, lang);
        click(frame, "Continue", "Continuar");
        Thread.sleep(600);
        snapshot(frame, out + "-2-main.png");

        AbstractButton primary =
                find(frame, "Install", "Update", "Instalar", "Actualizar", "Atualizar");
        System.out.println("[test] main button: " + primary.getText());
        SwingUtilities.invokeAndWait(primary::doClick);
        Thread.sleep(800);
        snapshot(frame, out + "-3-running.png");

        AbstractButton finish =
                waitFor(
                        () -> {
                            AbstractButton b = find(frame, "Finish", "Finalizar");
                            return b != null && b.isShowing() ? b : null;
                        },
                        180);
        Thread.sleep(400);
        snapshot(frame, out + "-4-end.png");
        System.out.println("[test] end: button " + finish.getText() + " visible");
        System.exit(0);
    }

    interface Probe<T> {
        T get() throws Exception;
    }

    static <T> T waitFor(Probe<T> p, int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            Object[] box = new Object[1];
            SwingUtilities.invokeAndWait(
                    () -> {
                        try {
                            box[0] = p.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            if (box[0] != null) {
                @SuppressWarnings("unchecked")
                T t = (T) box[0];
                return t;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("timed out waiting for the window");
    }

    static void click(Container root, String... texts) throws Exception {
        AbstractButton b = find(root, texts);
        if (b == null) {
            throw new IllegalStateException("button not found: " + List.of(texts));
        }
        SwingUtilities.invokeAndWait(b::doClick);
    }

    /** First visible button with one of the texts. */
    static AbstractButton find(Container root, String... texts) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton b
                    && b.isShowing()
                    && List.of(texts).contains(b.getText())) {
                return b;
            }
            if (c instanceof Container k) {
                AbstractButton b = find(k, texts);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static void snapshot(JFrame frame, String file) throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        Container c = frame.getContentPane();
                        BufferedImage img =
                                new BufferedImage(
                                        c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = img.createGraphics();
                        c.paint(g);
                        g.dispose();
                        ImageIO.write(img, "png", new File(file));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        System.out.println("[test] " + file);
    }
}
