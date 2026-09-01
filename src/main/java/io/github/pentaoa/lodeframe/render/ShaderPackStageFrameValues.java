package io.github.pentaoa.lodeframe.render;

record ShaderPackStageFrameValues(
        ShaderPackUniformLayout.FrameValues delegate,
        int renderStage
) implements ShaderPackUniformLayout.FrameValues {
    @Override
    public int integer(final String name) {
        return name.equals("renderStage") ? this.renderStage : this.delegate.integer(name);
    }

    @Override
    public int integerComponent(final String name, final int component) {
        return this.delegate.integerComponent(name, component);
    }

    @Override
    public float floatComponent(final String name, final int component) {
        return this.delegate.floatComponent(name, component);
    }

    @Override
    public float matrixComponent(final String name, final int columns, final int column, final int row) {
        return this.delegate.matrixComponent(name, columns, column, row);
    }
}
