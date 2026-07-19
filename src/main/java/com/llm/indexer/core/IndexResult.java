package com.llm.indexer.core;

import java.nio.file.Path;

public record IndexResult(
    String treeMd,
    String skeletonMd,
    String depsMd,
    Path dbPath,
    int filesTotal,
    int filesChanged
) {}
