package io.github.itsjanghoon.shortstop.engine

import java.io.File

/** Reads ../shared straight from the repo, the same files the app copies into its assets. */
object TestShared {
    val dir = File(System.getProperty("shortstop.shared") ?: "../../shared")
    val shared: Shared by lazy { Shared.load { File(dir, it).readText() } }
    val engine: Engine by lazy { Engine(shared) }
}
