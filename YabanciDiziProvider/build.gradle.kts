val pluginClass = "com.example.HdFilmCehennemiPlugin"

version = 1

cloudstream {
    language = "tr"
    description = "HDFilmCehennemi film ve dizi eklentisi"
    authors = listOf("EmRe-35")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.hdfilmcehennemi.nl/favicon.ico"
}

dependencies {
    implementation("org.jspecify:jspecify:1.0.0")
}
