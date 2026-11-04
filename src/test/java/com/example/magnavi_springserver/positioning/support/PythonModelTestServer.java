package com.example.magnavi_springserver.positioning.support;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 모델을 루프백의 임의 포트에서 실행하며 Python 업무 앱·DB에는 연결하지 않는다. */
public final class PythonModelTestServer implements AutoCloseable {
    private static final JsonMapper JSON = new JsonMapper();
    private final Path root;
    private final Path directory;
    private final Process process;
    private final int port;

    /** 준비가 끝난 자식 프로세스와 진단 파일의 소유권을 보관한다. */
    private PythonModelTestServer(Path root, Path directory, Process process, int port) {
        this.root = root;
        this.directory = directory;
        this.process = process;
        this.port = port;
    }

    /** 지정한 합성 설정을 임시 사본으로 만들며 원본 설정·모델 파일은 수정하지 않는다. */
    public static PythonModelTestServer start(Path root, Path directory, int maxStreams) throws Exception {
        root = root.toAbsolutePath();
        directory = directory.toAbsolutePath();
        Files.createDirectories(directory);
        var config = (ObjectNode) JSON.readTree(root.resolve("config/positioning.synthetic.json").toFile());
        config.put("host", "127.0.0.1");
        config.put("port", 0);
        config.put("max_streams", maxStreams);
        Path configFile = directory.resolve("config.json");
        Path readyFile = directory.resolve("ready.json");
        Files.writeString(configFile, JSON.writeValueAsString(config));
        var builder = new ProcessBuilder(root.resolve("build/streaming-venv/bin/python").toString(),
                "-m", "positioning", "--config", configFile.toString(), "--ready-file", readyFile.toString())
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("python.log").toFile());
        builder.environment().put("PYTHONPATH", root + File.pathSeparator + root.resolve("build/generated"));
        Process process = builder.start();
        try {
            int port = awaitPort(process, readyFile);
            return new PythonModelTestServer(root, directory, process, port);
        } catch (Exception | AssertionError failure) {
            // 준비 실패 시에도 시작한 자식 프로세스가 다음 테스트에 남지 않게 한다.
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw failure;
        }
    }

    /** 파일 검증·기준 추론·gRPC 준비가 모두 끝난 표시만 기동 성공으로 인정한다. */
    private static int awaitPort(Process process, Path readyFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.exists(readyFile) && Files.size(readyFile) > 0) {
                try {
                    JsonNode metadata = JSON.readTree(readyFile.toFile());
                    if (metadata.path("ready").asBoolean() && metadata.path("port").asInt() > 0) {
                        return metadata.get("port").asInt();
                    }
                } catch (JacksonException incompleteWrite) {
                    // 준비 파일을 쓰는 중 읽었다면 완성된 JSON을 다음 관찰에서 확인한다.
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Python 모델 준비 실패: " + readyFile.getParent().resolve("python.log"));
    }

    /** 기준 입력·결과를 실제 실행한 Python 저장소에서 읽도록 경로를 제공한다. */
    public Path root() { return root; }

    /** 운영 주소 대신 테스트 프로세스가 실제로 바인딩한 임의 포트를 제공한다. */
    public int port() { return port; }

    /** 정상 종료 기한을 넘기면 강제 회수하고 실패로 보고하며 진단 로그는 보존한다. */
    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Python 종료 시간 초과: " + directory.resolve("python.log"));
        }
        if (process.exitValue() != 0 || Files.exists(directory.resolve("ready.json"))) {
            throw new AssertionError("Python 정상 종료 확인 실패: " + directory.resolve("python.log"));
        }
    }
}
