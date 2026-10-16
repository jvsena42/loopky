package com.github.jvsena42.loopky.mcp

fun main() {
    val config = McpConfig.fromEnvironment()
    // Wired in a later commit, once the OAuth and tool halves exist.
    println("loopky-mcp for ${config.environment.name.lowercase()} at ${config.publicUrl}")
}
