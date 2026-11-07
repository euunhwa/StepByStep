plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped") } }
tasks.register<JavaExec>("exportDemoFixtures") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.easycar.core.DemoExporter")
    args(rootProject.file("app/src/main/assets/demo").absolutePath)
}
tasks.register<JavaExec>("auditScenarios") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.easycar.core.ScenarioAudit")
    args(rootProject.file("app/src/main/assets/profiles/auto-coach-draft.json").absolutePath)
}
tasks.register<JavaExec>("exportDocumentFixtures") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.easycar.core.DocumentFixtureExporter")
    args(rootProject.projectDir.absolutePath)
}
tasks.register<JavaExec>("recordQuerySmoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.easycar.core.RecordQuerySmoke")
    args(rootProject.projectDir.absolutePath)
}
