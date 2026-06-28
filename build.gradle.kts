// minecraft-plugin-money — the economy/currency plugin.
// Depends on core as `compileOnly`: core is a separate plugin on the server,
// so we never bundle it. The shaded jar only relocates third-party libs (none
// yet) to avoid clashing with other plugins on the same server.

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(project(":minecraft-plugin-core"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // relocate("com.example.shadedlib", "com.mrfermz.mcplugins.money.libs.shadedlib")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
