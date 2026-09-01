package io.github.pentaoa.lodeframe.client.shader;

public record ShaderPackSelection(String fileName, String displayName) {
    public static final ShaderPackSelection NONE = new ShaderPackSelection("", "");

    public boolean isPresent() {
        return !this.fileName.isEmpty();
    }
}
