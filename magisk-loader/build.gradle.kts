/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.apache.commons.codec.binary.Hex
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.kotlin.dsl.register
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.resopt)
}

val moduleName = "LSPosed"
val moduleBaseId = "lsposed"
val authors = "JingMatrix & LSPosed Developers"

val injectedPackageName: String by rootProject.extra
val injectedPackageUid: Int by rootProject.extra

val defaultManagerPackageName: String by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra

android {
    flavorDimensions += "api"

    buildFeatures {
        prefab = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.lsposed.lspd"
        multiDexEnabled = false

        buildConfigField(
            "String",
            "DEFAULT_MANAGER_PACKAGE_NAME",
            """"$defaultManagerPackageName""""
        )
        buildConfigField("String", "MANAGER_INJECTED_PKG_NAME", """"$injectedPackageName"""")
        buildConfigField("int", "MANAGER_INJECTED_UID", """$injectedPackageUid""")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments += "-DMODULE_NAME=${name.lowercase()}_$moduleBaseId"
                    arguments += "-DAPI=${name.lowercase()}"
                }
            }
        }

        create("Zygisk") {
            dimension = "api"
            externalNativeBuild {
                cmake {
                    arguments += "-DAPI_VERSION=1"
                }
            }
        }
    }
    namespace = "org.lsposed.lspd"
}
abstract class Injected @Inject constructor(val magiskDir: String) {
    @get:Inject
    abstract val factory: ObjectFactory
}

dependencies {
    implementation(projects.core)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.managerService)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

val zipAll = tasks.register("zipAll") {
    group = "LSPosed"
}

fun afterEval() = android.applicationVariants.forEach { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
    val variantLowered = variant.name.lowercase()
    val buildTypeCapped = variant.buildType.name.replaceFirstChar { it.uppercase() }
    val buildTypeLowered = variant.buildType.name.lowercase()
    val flavorCapped = variant.flavorName!!.replaceFirstChar { it.uppercase() }
    val flavorLowered = variant.flavorName!!.lowercase()

    val magiskDir = layout.buildDirectory.dir("magisk/$variantLowered")

    val moduleId = "${flavorLowered}_$moduleBaseId"
    val zipFileName = "$moduleName-v$verName-$verCode-${flavorLowered}-$buildTypeLowered.zip"

    val prepareMagiskFilesTask = tasks.register<Sync>("prepareMagiskFiles$variantCapped") {
        group = "LSPosed"
        dependsOn(
            "assemble$variantCapped",
            ":app:package$buildTypeCapped",
            ":daemon:package$buildTypeCapped",
            ":dex2oat:externalNativeBuild${buildTypeCapped}"
        )
        into(magiskDir)
        from("${rootProject.projectDir}/README.md")
        from("$projectDir/magisk_module") {
            exclude("module.prop", "customize.sh", "daemon")
        }
        from("$projectDir/magisk_module") {
            include("module.prop")
            expand(
                "moduleId" to moduleId,
                "versionName" to "v$verName",
                "versionCode" to verCode,
                "authorList" to authors,
                "updateJson" to "https://raw.githubusercontent.com/JingMatrix/LSPosed/master/magisk-loader/update/${flavorLowered}.json",
                "requirement" to when (flavorLowered) {
                    "zygisk" -> "Requires Magisk 26.0+ and Zygisk enabled"
                    else -> "No further requirements"
                },
                "api" to flavorCapped,
            )
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        from("$projectDir/magisk_module") {
            include("customize.sh", "daemon")
            val tokens = mapOf(
                "FLAVOR" to flavorLowered,
                "DEBUG" to if (buildTypeLowered == "debug") "true" else "false"
            )
            filter<ReplaceTokens>("tokens" to tokens)
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        from(project(":app").tasks.getByName("package$buildTypeCapped").outputs) {
            include("*.apk")
            rename(".*\\.apk", "manager.apk")
        }
        from(project(":daemon").tasks.getByName("package$buildTypeCapped").outputs) {
            include("*.apk")
            rename(".*\\.apk", "daemon.apk")
        }
        into("lib") {
            val libDir = variantCapped + "/strip${variantCapped}DebugSymbols"
            from(layout.buildDirectory.dir("intermediates/stripped_native_libs/$libDir/out/lib")) {
                include("**/liblspd.so")
            }
        }
        into("bin") {
            from(project(":dex2oat").layout.buildDirectory.dir("intermediates/cmake/$buildTypeLowered/obj")) {
                include("**/dex2oat")
                include("**/liboat_hook.so")
            }
        }
        val dexOutPath = if (buildTypeLowered == "release")
            layout.buildDirectory.dir("intermediates/dex/$variantCapped/minify${variantCapped}WithR8")
        else
            layout.buildDirectory.dir("intermediates/dex/$variantCapped/mergeDex$variantCapped")
        into("framework") {
            from(dexOutPath)
            rename("classes.dex", "lspd.dex")
        }
        val injected = objects.newInstance<Injected>(magiskDir.get().asFile.path)
        doLast {
            injected.factory.fileTree().from(injected.magiskDir).visit {
                if (isDirectory) return@visit
                val md = MessageDigest.getInstance("SHA-256")
                file.forEachBlock(4096) { bytes, size ->
                    md.update(bytes, 0, size)
                }
                File(file.path + ".sha256").writeText(Hex.encodeHexString(md.digest()))
            }
        }
    }

    val zipTask = tasks.register<Zip>("zip${variantCapped}") {
        group = "LSPosed"
        dependsOn(prepareMagiskFilesTask)
        archiveFileName = zipFileName
        destinationDirectory = file("$projectDir/release")
        from(magiskDir)
    }

    zipAll.dependsOn(zipTask)

    val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
    val pushTask = tasks.register<Exec>("push${variantCapped}") {
        group = "LSPosed"
        dependsOn(zipTask)
        workingDir("${projectDir}/release")
        commandLine(adb, "push", zipFileName, "/data/local/tmp/")
    }
    val flashMagiskTask = tasks.register<Exec>("flashMagisk${variantCapped}") {
        group = "LSPosed"
        dependsOn(pushTask)
        commandLine(
            adb, "shell", "su", "-c",
            "magisk --install-module /data/local/tmp/${zipFileName}"
        )
    }
    tasks.register<Exec>("flashMagiskAndReboot${variantCapped}") {
        group = "LSPosed"
        dependsOn(flashMagiskTask)
        commandLine(adb, "shell", "su", "-c", "/system/bin/svc", "power", "reboot")
    }
    val flashKsuTask = tasks.register<Exec>("flashKsu${variantCapped}") {
        group = "LSPosed"
        dependsOn(pushTask)
        commandLine(
            adb, "shell", "su", "-c",
            "ksud module install /data/local/tmp/${zipFileName}"
        )
    }
    tasks.register<Exec>("flashKsuAndReboot${variantCapped}") {
        group = "LSPosed"
        dependsOn(flashKsuTask)
        commandLine(adb, "shell", "su", "-c", "/system/bin/svc", "power", "reboot")
    }
    val flashAPatchTask = tasks.register<Exec>("flashAPatch${variantCapped}") {
        group = "LSPosed"
        dependsOn(pushTask)
        commandLine(
            adb, "shell", "su", "-c",
            "apd module install /data/local/tmp/${zipFileName}"
        )
    }
    tasks.register<Exec>("flashAPatchAndReboot${variantCapped}") {
        group = "LSPosed"
        dependsOn(flashAPatchTask)
        commandLine(adb, "shell", "su", "-c", "/system/bin/svc", "power", "reboot")
    }
}

afterEvaluate {
    afterEval()
}

val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
val killLspd = tasks.register<Exec>("killLspd") {
    group = "LSPosed"
    commandLine(adb, "shell", "su", "-c", "killall", "lspd")
    isIgnoreExitValue = true
}
val pushDaemon = tasks.register<Exec>("pushDaemon") {
    group = "LSPosed"
    dependsOn(":daemon:assembleDebug")
    workingDir(project(":daemon").layout.buildDirectory.dir("outputs/apk/debug"))
    commandLine(adb, "push", "daemon-debug.apk", "/data/local/tmp/daemon.apk")
}
val pushDaemonNative = tasks.register<Exec>("pushDaemonNative") {
    group = "LSPosed"
    dependsOn(":daemon:assembleDebug")
    doFirst {
        val abi: String = ByteArrayOutputStream().use { outputStream ->
            exec {
                commandLine(adb, "shell", "getprop", "ro.product.cpu.abi")
                standardOutput = outputStream
            }
            outputStream.toString().trim()
        }
        workingDir(project(":daemon").layout.buildDirectory.dir("intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/$abi"))
    }
    commandLine(adb, "push", "libdaemon.so", "/data/local/tmp/libdaemon.so")
}
val reRunDaemon = // tricky to pass a minus number to avoid the injection warning
    tasks.register<Exec>("reRunDaemon"
    ) // tricky to pass a minus number to avoid the injection warning
    {
        group = "LSPosed"
        dependsOn(pushDaemon, pushDaemonNative, killLspd)
        // tricky to pass a minus number to avoid the injection warning
        commandLine(
            adb, "shell", "ASH_STANDALONE=1", "su", "-mm", "-pc",
            "/data/adb/magisk/busybox sh /data/adb/modules/*_lsposed/service.sh --system-server-max-retry=-1&"
        )
        isIgnoreExitValue = true
    }
val tmpApk = "/data/local/tmp/manager.apk"
val pushApk = tasks.register<Exec>("pushApk") {
    group = "LSPosed"
    dependsOn(":app:assembleDebug")
    doFirst {
        exec {
            commandLine(adb, "shell", "su", "-c", "rm", "-f", tmpApk)
        }
    }
    workingDir(project(":app").layout.buildDirectory.dir("outputs/apk/debug"))
    commandLine(adb, "push", "app-debug.apk", tmpApk)
}
val openApp = tasks.register<Exec>("openApp") {
    group = "LSPosed"
    commandLine(
        adb, "shell",
        "am", "start", "-c", "org.lsposed.manager.LAUNCH_MANAGER",
        "com.android.shell/.BugreportWarningActivity"
    )
}
tasks.register("reRunApp") {
    group = "LSPosed"
    dependsOn(pushApk)
    finalizedBy(reRunDaemon)
}

evaluationDependsOn(":app")
evaluationDependsOn(":daemon")
