package xin.chunming;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DouyinLiveFetcher {

    private static final String STREAM_URL = "";  // 填入直播流URL
    private static final int MAX_ERROR_COUNT = 30;
    private static final int RESTART_DELAY_SECONDS = 5;
    // FFmpeg运行超过此时长（毫秒）认为本次拉流有效，重置错误计数
    private static final long VALID_STREAM_THRESHOLD_MS = 10_000;

    private final String streamUrl;
    private final String ffmpegPath;
    private final String outputFile;

    private int errorCount = 0;
    private int tsCount = 0;
    private Process process = null;
    private volatile boolean running = true;

    public DouyinLiveFetcher(String streamUrl, String ffmpegPath, String outputFile) {
        this.streamUrl = streamUrl;
        this.ffmpegPath = ffmpegPath;
        this.outputFile = outputFile;
    }

    private List<String> buildFfmpegCommand() {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(streamUrl);
        command.add("-c");
        command.add("copy");
        command.add("-f");
        command.add("mpegts");
        command.add(tsCount + "_" + outputFile);
        return command;
    }

    private void startFfmpeg() throws IOException {
        tsCount++;
        List<String> cmd = buildFfmpegCommand();
        System.out.println("[INFO] 启动FFmpeg (#" + tsCount + "): " + String.join(" ", cmd));
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.redirectErrorStream(true); // 合并stderr到stdout，方便统一读取
        process = processBuilder.start();
    }

    private boolean is404Error(String output) {
        return output.contains("404") || output.contains("HTTP error 404")
                || output.contains("Not Found");
    }

    private void monitorStream() {
        while (running) {
            if (errorCount >= MAX_ERROR_COUNT) {
                System.out.println("[ERROR] 连续失败已达 " + MAX_ERROR_COUNT + " 次，判定直播结束，程序退出");
                break;
            }

            long startTime = System.currentTimeMillis();

            try {
                startFfmpeg();

                // 读取FFmpeg全部输出（stdout+stderr合并）
                StringBuilder outputSb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[FFmpeg] " + line);
                        outputSb.append(line).append("\n");
                    }
                }

                int exitCode = process.waitFor();
                long duration = System.currentTimeMillis() - startTime;
                String output = outputSb.toString();

                System.out.println("[INFO] FFmpeg退出，exit=" + exitCode
                        + "，本次运行时长=" + duration / 1000 + "s");

                if (duration >= VALID_STREAM_THRESHOLD_MS) {
                    // 本次确实拉到了有效流，说明推流者网络刚才是通的，重置计数
                    System.out.println("[INFO] 本次拉流有效（运行 " + duration / 1000
                            + "s），重置错误计数（原计数: " + errorCount + "）");
                    errorCount = 0;
                }

                if (exitCode == 0) {
                    // ffmpeg正常退出：流正常结束或被我们主动终止
                    if (!running) {
                        // 是我们stop()触发的，直接退出循环
                        break;
                    }
                    errorCount++;
                    System.out.println("[INFO] 流中断或结束，正常退出。错误计数: "
                            + errorCount + "/" + MAX_ERROR_COUNT);
                } else if (is404Error(output)) {
                    errorCount++;
                    System.out.println("[WARNING] 404 — 推流者网络可能卡顿，错误计数: "
                            + errorCount + "/" + MAX_ERROR_COUNT);
                } else {
                    errorCount++;
                    System.out.println("[ERROR] FFmpeg异常退出 exit=" + exitCode
                            + "，错误计数: " + errorCount + "/" + MAX_ERROR_COUNT);
                }

            } catch (IOException e) {
                errorCount++;
                System.err.println("[ERROR] 启动FFmpeg失败: " + e.getMessage()
                        + "，错误计数: " + errorCount + "/" + MAX_ERROR_COUNT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[INFO] 监控线程被中断，退出");
                break;
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            // 每次失败后等待一段时间再重试
            if (running && errorCount < MAX_ERROR_COUNT) {
                System.out.println("[INFO] " + RESTART_DELAY_SECONDS + " 秒后重试...\n");
                try {
                    Thread.sleep(RESTART_DELAY_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        System.out.println("[INFO] 监控循环结束");
    }

    public void stop() {
        System.out.println("[INFO] 收到停止指令");
        running = false;
        if (process != null && process.isAlive()) {
            System.out.println("[INFO] 正在终止FFmpeg进程...");
            process.destroy(); // 先尝试优雅退出
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    System.out.println("[INFO] FFmpeg进程已强制终止");
                } else {
                    System.out.println("[INFO] FFmpeg进程已正常终止");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    public void start() {
        System.out.println("==================================================");
        System.out.println("抖音直播拉流守护程序");
        System.out.println("直播流URL : " + streamUrl);
        System.out.println("输出文件  : " + outputFile);
        System.out.println("FFmpeg路径: " + ffmpegPath);
        System.out.println("有效流阈值: " + VALID_STREAM_THRESHOLD_MS / 1000 + "s");
        System.out.println("最大重试数: " + MAX_ERROR_COUNT);
        System.out.println("重试间隔  : " + RESTART_DELAY_SECONDS + "s");
        System.out.println("==================================================\n");

        monitorStream();
    }

    public static void main(String[] args) {
        String streamUrl  = args.length > 0 ? args[0] : STREAM_URL;
        String outputFile = args.length > 1 ? args[1] : "output.ts";
        String ffmpegPath = args.length > 2 ? args[2] : "ffmpeg";

        if (streamUrl == null || streamUrl.isEmpty()) {
            System.err.println("用法: java DouyinLiveFetcher <直播流URL> [输出文件名] [ffmpeg路径]");
            System.err.println("示例: java DouyinLiveFetcher 'https://pull-flv.douyincdn.com/...' output.ts");
            //System.exit(1);
        }

        DouyinLiveFetcher fetcher = new DouyinLiveFetcher(streamUrl, ffmpegPath, outputFile);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[INFO] 收到关闭信号，正在退出...");
            fetcher.stop();
        }));

        fetcher.start();
    }
}
