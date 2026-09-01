package io.github.pentaoa.lodeframe.client.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ShaderPackDiscovery {
    private ShaderPackDiscovery() {
    }

    public static List<ShaderPackSelection> discover(final Path shaderPackDirectory) throws IOException {
        Files.createDirectories(shaderPackDirectory);
        try (Stream<Path> children = Files.list(shaderPackDirectory)) {
            return children
                    .filter(ShaderPackDiscovery::isPackCandidate)
                    .map(ShaderPackDiscovery::toSelection)
                    .sorted(Comparator.comparing(
                            ShaderPackSelection::displayName,
                            String.CASE_INSENSITIVE_ORDER
                    ).thenComparing(ShaderPackSelection::fileName))
                    .toList();
        }
    }

    private static boolean isPackCandidate(final Path path) {
        if (Files.isDirectory(path)) {
            return Files.isDirectory(path.resolve("shaders"));
        }
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static ShaderPackSelection toSelection(final Path path) {
        String fileName = path.getFileName().toString();
        String displayName = fileName.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        return new ShaderPackSelection(fileName, displayName);
    }
}
