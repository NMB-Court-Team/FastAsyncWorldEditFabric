import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
    id("maven-publish")
    id("antlr")
    id("com.gradleup.shadow") version "9.3.2"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("fastasyncworldedit") {
            sourceSet("main")
        }
    }

    runs.named("client").configure {
        runDirectory.set(file("run-client"))
        programArguments.addAll(
            "--username",
            "DevPlayer",
            "--uuid",
            "1-0-1-0-1",
            "--width",
            "2880",
            "--height",
            "1920"
        )
    }

    runs.named("server").configure {
        runDirectory.set(file("run-server"))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.enginehub.org/libs-release/") {
        name = "EngineHub"
        content {
            includeGroup("org.enginehub")
            includeGroup("org.enginehub.piston")
            includeGroup("org.enginehub.piston.core-ap")
            includeGroup("org.enginehub.lin-bus")
            includeGroup("org.enginehub.lin-bus.format")
            includeGroup("org.enginehub.worldeditcui-protocol")
            includeGroup("com.sk89q")
            includeGroup("com.sk89q.lib")
            includeGroup("com.sk89q.worldedit")
        }
    }
    maven("https://maven.enginehub.org/repo/") {
        name = "EngineHubLegacy"
        content {
            includeGroup("org.enginehub")
            includeGroup("org.enginehub.piston")
            includeGroup("org.enginehub.piston.core-ap")
            includeGroup("org.enginehub.lin-bus")
            includeGroup("org.enginehub.lin-bus.format")
            includeGroup("org.enginehub.worldeditcui-protocol")
            includeGroup("com.sk89q")
            includeGroup("com.sk89q.lib")
            includeGroup("com.sk89q.worldedit")
        }
    }
}

val worldeditRelocate = configurations.create("worldeditRelocate") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val worldeditLangZip = layout.buildDirectory.file("worldedit-lang/worldedit-lang-7.4.5+2246.zip")
val downloadWorldeditLang = tasks.register("downloadWorldeditLang") {
    outputs.file(worldeditLangZip)
    doLast {
        val destination = worldeditLangZip.get().asFile
        if (destination.isFile && destination.length() > 0L) {
            return@doLast
        }
        destination.parentFile.mkdirs()
        val source = uri(
            "https://repo.enginehub.org/language-files/com/sk89q/worldedit/worldedit-lang/7.4.5/worldedit-lang-7.4.5+2246.zip"
        ).toURL()
        source.openStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

val worldeditLibsJar = tasks.register<ShadowJar>("worldeditLibsJar") {
    configurations.set(listOf(worldeditRelocate))
    archiveBaseName.set("worldedit-relocated-libs")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("worldedit-libs"))
    exclude("module-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    mergeServiceFiles()
    relocate("net.kyori.text", "com.sk89q.worldedit.util.formatting.text")
    relocate("net.kyori.minecraft", "com.sk89q.worldedit.util.kyori")
    dependencies {
        exclude(dependency("com.google.guava:guava"))
        exclude(dependency("com.google.code.gson:gson"))
        exclude(dependency("com.google.errorprone:error_prone_annotations"))
        exclude(dependency("com.google.guava:failureaccess"))
        exclude(dependency("org.checkerframework:checker-qual"))
        exclude(dependency("org.jetbrains:annotations"))
        exclude(dependency("org.apache.logging.log4j:log4j-api"))
        exclude(dependency("com.google.code.findbugs:jsr305"))
        exclude(dependency("org.jspecify:jspecify"))
        exclude(dependency("com.google.j2objc:j2objc-annotations"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk8"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk7"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-common"))
    }
}

tasks.named<ShadowJar>("shadowJar") {
    enabled = false
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    val relocated = files(worldeditLibsJar.flatMap { it.archiveFile })
    relocated.builtBy(worldeditLibsJar)
    implementation(relocated)
    include(relocated)
    annotationProcessor(relocated)

    worldeditRelocate("net.kyori:text-api:3.0.4")
    worldeditRelocate("net.kyori:text-serializer-gson:3.0.4")
    worldeditRelocate("net.kyori:text-serializer-legacy:3.0.4")
    worldeditRelocate("net.kyori:text-serializer-plain:3.0.4")
    worldeditRelocate("org.enginehub.piston:core:0.6.0")
    worldeditRelocate("org.enginehub.piston:default-impl:0.6.0")
    worldeditRelocate("org.enginehub.piston.core-ap:runtime:0.6.0")
    worldeditRelocate("org.enginehub.piston.core-ap:annotations:0.6.0")
    worldeditRelocate("org.enginehub.piston.core-ap:processor:0.6.0")

    compileOnly("com.google.auto.value:auto-value-annotations:1.10.4")
    annotationProcessor("com.google.auto.value:auto-value:1.10.4")
    annotationProcessor("com.google.guava:guava:33.6.0-jre")
    annotationProcessor("com.google.code.gson:gson:2.14.0")

    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.48.0")
    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    compileOnly("com.google.j2objc:j2objc-annotations:3.0.0")
    compileOnly("de.schlichtherle:truezip:6.8.4")

    implementation(platform("org.enginehub.lin-bus:lin-bus-bom:0.2.0"))
    include(implementation("org.enginehub.lin-bus:lin-bus-common")!!)
    include(implementation("org.enginehub.lin-bus:lin-bus-stream")!!)
    include(implementation("org.enginehub.lin-bus:lin-bus-tree")!!)
    include(implementation("org.enginehub.lin-bus.format:lin-bus-format-snbt")!!)

    include(implementation("org.mozilla:rhino-runtime:1.7.13")!!)
    include(implementation("com.sk89q:jchronic:0.2.4a")!!)
    include(implementation("com.sk89q.lib:jlibnoise:1.0.0")!!)
    include(implementation("org.yaml:snakeyaml:2.0")!!)

    antlr("org.antlr:antlr4:4.13.2")
    include(implementation("org.antlr:antlr4-runtime:4.13.2")!!)

    include(implementation("org.enginehub.worldeditcui-protocol:worldeditcui-protocol-common-mc26.2:4.0.3")!!)
    include(implementation("org.enginehub.worldeditcui-protocol:worldeditcui-protocol-fabric-mc26.2:4.0.3")!!)
}

tasks.named<org.gradle.api.plugins.antlr.AntlrTask>("generateGrammarSource") {
    val pkg = "com.sk89q.worldedit.antlr"
    outputDirectory = file("build/generated-src/antlr/main/${pkg.replace('.', '/')}")
    arguments = listOf("-visitor", "-package", pkg, "-Xexact-output-dir")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version")!!,
            "loader_version" to project.property("loader_version")!!,
            "kotlin_loader_version" to project.property("kotlin_loader_version")!!
        )
    }

    from(worldeditLangZip) {
        rename { "i18n.zip" }
        into("lang")
    }
    dependsOn(downloadWorldeditLang)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
    options.compilerArgs.add("-Aarg.name.key.prefix=")
    dependsOn(worldeditLibsJar)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
    dependsOn(worldeditLibsJar)
}

tasks.jar {
    from("LICENSE.txt") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
    from("NOTICE.txt")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    repositories {
    }
}
