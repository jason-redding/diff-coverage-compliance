package com.witcraft.diffcoveragecompliance

import groovy.xml.slurpersupport.GPathResult

import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

final class SourcePathWithXmlNode implements Path {
    private final Path path
    private final GPathResult node

    SourcePathWithXmlNode(Path path, GPathResult node) {
        this.path = path
        this.node = node
    }

    Path getPath() { path }

    GPathResult getNode() { node }

    @Override FileSystem getFileSystem() { return path.getFileSystem() }

    @Override boolean isAbsolute() { return path.isAbsolute() }

    @Override Path getRoot() { return path.getRoot() }

    @Override Path getFileName() { return path.getFileName() }

    @Override Path getParent() { return path.getParent() }

    @Override int getNameCount() { return path.getNameCount() }

    @Override Path getName(int index) { return path.getName(index) }

    @Override Path subpath(int beginIndex, int endIndex) { return path.subpath(beginIndex, endIndex) }

    @Override boolean startsWith(Path other) { return path.startsWith(other) }

    @Override boolean endsWith(Path other) { return path.endsWith(other) }

    @Override Path normalize() { return path.normalize() }

    @Override Path resolve(Path other) { return path.resolve(other) }

    @Override Path relativize(Path other) { return path.relativize(other) }

    @Override URI toUri() { return path.toUri() }

    @Override Path toAbsolutePath() { return path.toAbsolutePath() }

    @Override Path toRealPath(LinkOption... options) throws IOException { return path.toRealPath(options) }

    @Override WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        return path.register(watcher, events, modifiers)
    }

    @Override int compareTo(Path other) { path.compareTo(other) }

    @Override String toString() { return "{path=\"${path}\", node=\"${node?.text()}\"}" }

    @Override int hashCode() { Objects.hashCode(path) }
}