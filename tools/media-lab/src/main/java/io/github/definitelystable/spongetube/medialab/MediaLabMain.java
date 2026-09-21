package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public final class MediaLabMain {

    private MediaLabMain() {
    }

    public static void main(String[] args) {
        MediaLabConfig config;
        try {
            config = CliArguments.parse(args);
        } catch (CliArguments.HelpRequested help) {
            System.out.print(CliArguments.usage());
            return;
        } catch (IllegalArgumentException invalid) {
            System.err.println("media-lab: " + invalid.getMessage());
            System.err.print(CliArguments.usage());
            System.exit(2);
            return;
        }

        try {
            run(config);
        } catch (IOException exception) {
            System.err.println("media-lab startup failure: " + exception.getMessage());
            System.exit(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void run(MediaLabConfig config) throws IOException, InterruptedException {
        MediaLabServer server = MediaLabServer.create(config);
        CountDownLatch stopped = new CountDownLatch(1);

        Thread shutdownHook = new Thread(() -> {
            try {
                server.close();
            } catch (IOException exception) {
                System.err.println("media-lab shutdown failure: " + exception.getMessage());
            } finally {
                stopped.countDown();
            }
        }, "media-lab-shutdown");

        Runtime.getRuntime().addShutdownHook(shutdownHook);

        server.start();
        System.out.println(server.readyJson());
        System.out.flush();

        try {
            stopped.await();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already in progress.
            }
            server.close();
        }
    }
}
