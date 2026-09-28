val pluginClass = "com.example.yabancidizi.YabanciDiziProvider"

version = 1

cloudstream {
    language = "tr"
    description = "yabancidizi.news sitesi için Cloudstream eklentisi"
    authors = listOf("EmRe-35")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://yabancidizi.news/favicon.ico"
}

dependencies {
    // Jsoup zaten Cloudstream içinde var, ayrıca eklemeye gerek yok
}
