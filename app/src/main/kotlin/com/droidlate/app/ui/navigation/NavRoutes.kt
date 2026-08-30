package com.droidlate.app.ui.navigation

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object Home : Screen("home")

    object Dashboard : Screen("dashboard/{projectId}") {
        fun createRoute(projectId: String): String {
            val encodedId = URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString())
            return "dashboard/$encodedId"
        }
    }

    object Editor : Screen("editor/{projectId}/{langFolder}") {
        fun createRoute(projectId: String, langFolder: String): String {
            val encodedId = URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString())
            val encodedLang = URLEncoder.encode(langFolder, StandardCharsets.UTF_8.toString())
            return "editor/$encodedId/$encodedLang"
        }
    }

    companion object {
        fun decodeParam(param: String): String {
            return URLDecoder.decode(param, StandardCharsets.UTF_8.toString())
        }
    }
}
