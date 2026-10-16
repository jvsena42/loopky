plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

/**
 * The hosted remote MCP server (#214, #486): Claude's connectors reach Loopky through it, for the
 * chat-only audience the `loopky` binary can never serve.
 *
 * A binding over `:cli-core`'s command functions, never a second implementation of them — the
 * same reason `:cli` exists as a JVM target at all (Architecture.md §13.1). A plain JVM, not a
 * native image: it runs in a container, so the single-file constraints `:cli` lives under do
 * not apply, and nothing here may be copied into `:cli` on the assumption that they do.
 */
/**
 * The agent skill, copied onto the classpath so `McpInstructions` serves its card-writing rules
 * as the server's `instructions` (#486). Copied at build time rather than typed into a string, so
 * the connector and Claude Code teach one set of rules — a hand-kept copy would be a second skill
 * nobody evaluates.
 */
val copyAgentSkill = tasks.register<Copy>("copyAgentSkill") {
    from(rootProject.file("plugins/loopky/skills/loopky/SKILL.md"))
    into(layout.buildDirectory.dir("generated/skill/com/github/jvsena42/loopky/mcp"))
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        resources.srcDir(layout.buildDirectory.dir("generated/skill"))
    }
}

tasks.named("processResources") { dependsOn(copyAgentSkill) }

application {
    mainClass.set("com.github.jvsena42.loopky.mcp.MainKt")
    applicationName = "loopky-mcp"
}

dependencies {
    implementation(projects.cliCore)
    implementation(libs.koin.core)
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.zxing.core)
    implementation(libs.logback.classic)

    testImplementation(testFixtures(projects.cliCore))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
