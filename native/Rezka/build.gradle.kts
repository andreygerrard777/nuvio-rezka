// Bump on every behavioural change: Nuvio only re-downloads a plugin whose version grew.
version = 2

cloudstream {
    language = "uk"
    description = "HDRezka: фільми, серіали, мультфільми та аніме. Усі озвучки (українські першими), найкраща доступна якість (720p), субтитри."
    authors = listOf("nuvio-uk-providers")
    // 1 = working
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Cartoon", "Anime", "AnimeMovie")
    iconUrl = "https://www.google.com/s2/favicons?domain=rezka.ag&sz=128"
}
