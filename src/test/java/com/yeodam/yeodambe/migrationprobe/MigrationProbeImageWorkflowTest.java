package com.yeodam.yeodambe.migrationprobe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationProbeImageWorkflowTest {

    @TempDir
    private Path temp;

    @ParameterizedTest
    @CsvSource({
            "refs/heads/main,false,true,false",
            "refs/heads/main,true,false,true",
            "refs/heads/v2-probe,true,false,true"
    })
    void imageSelectionKeepsReleaseNotificationSeparate(
            String ref,
            String rehearsal,
            boolean notifyRelease,
            boolean probeTag
    ) throws Exception {
        Map<?, ?> selection = step("Select image reference");
        Path output = temp.resolve("output");
        var process = new ProcessBuilder("bash", "-c", selection.get("run").toString());
        process.environment().putAll(Map.of(
                "GITHUB_REF", ref,
                "GITHUB_SHA", "source-sha",
                "REHEARSAL", rehearsal,
                "IMAGE_NAME", "ghcr.io/example/backend",
                "GITHUB_OUTPUT", output.toString()
        ));
        Process running = process.redirectErrorStream(true).start();
        String diagnostics = new String(running.getInputStream().readAllBytes());
        assertThat(running.waitFor()).withFailMessage(diagnostics).isZero();
        String values = Files.readString(output);
        assertThat(values).contains("notify_release=" + notifyRelease);
        assertThat(values).contains("image=ghcr.io/example/backend:"
                + (probeTag ? "migration-probe-v2-sha-" : "sha-") + "source-sha");
    }

    @Test
    void nonMainRefCannotPublishWithoutRehearsalInput() throws Exception {
        Path output = temp.resolve("output");
        var process = new ProcessBuilder("bash", "-c", step("Select image reference").get("run").toString());
        process.environment().putAll(Map.of(
                "GITHUB_REF", "refs/heads/v2-probe",
                "GITHUB_SHA", "source-sha",
                "REHEARSAL", "false",
                "IMAGE_NAME", "ghcr.io/example/backend",
                "GITHUB_OUTPUT", output.toString()
        ));
        Process running = process.redirectErrorStream(true).start();
        running.getInputStream().readAllBytes();
        assertThat(running.waitFor()).isNotZero();
        assertThat(output).doesNotExist();
    }

    @Test
    void notifyStepRunsOnlyForExplicitReleaseOutputAndUsesSelectedImage() throws Exception {
        var notify = step("Notify Cloud V2 release manifest");
        assertThat(notify.get("if")).isEqualTo("${{ github.ref == 'refs/heads/main' && steps.image.outputs.notify_release == 'true' }}");
        assertThat(((Map<?, ?>) notify.get("env")).get("IMAGE"))
                .isEqualTo("${{ steps.image.outputs.image }}");
        assertThat(((Map<?, ?>) step("Build and push").get("with")).get("tags"))
                .isEqualTo("${{ steps.image.outputs.image }}");
    }

    private Map<?, ?> step(String name) throws Exception {
        Map<?, ?> yaml = new Yaml().load(Files.readString(Path.of(".github/workflows/backend-image.yml")));
        Map<?, ?> jobs = (Map<?, ?>) yaml.get("jobs");
        Map<?, ?> build = (Map<?, ?>) jobs.get("build-and-push");
        List<?> steps = (List<?>) build.get("steps");
        return steps.stream().map(value -> (Map<?, ?>) value)
                .filter(value -> name.equals(value.get("name")))
                .findFirst().orElseThrow();
    }
}
