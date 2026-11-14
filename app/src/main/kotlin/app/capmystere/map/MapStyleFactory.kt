package app.capmystere.map

import app.capmystere.theme.MapPaletteSpec

/**
 * Fabrique un style MapLibre complet à partir de la palette de carte d'un thème.
 * Source : tuiles vectorielles au schéma OpenMapTiles (OpenFreeMap par défaut).
 *
 * Côté voyageur ([forTraveler]) : aucun nom de lieu, aucun point d'intérêt — seule la voirie.
 */
object MapStyleFactory {
    data class Options(
        val forTraveler: Boolean,
        val streetNames: Boolean,
    )

    fun build(p: MapPaletteSpec, tilesBaseUrl: String, options: Options): String {
        val base = tilesBaseUrl.trimEnd('/')
        val layers = ArrayList<String>()
        layers += """{"id":"bg","type":"background","paint":{"background-color":"${p.land}"}}"""
        layers += """{"id":"landcover","type":"fill","source":"omt","source-layer":"landcover","filter":["in","class","wood","grass","farmland"],"paint":{"fill-color":"${p.park}","fill-opacity":0.55}}"""
        layers += """{"id":"park","type":"fill","source":"omt","source-layer":"park","paint":{"fill-color":"${p.park}","fill-opacity":0.7}}"""
        layers += """{"id":"water","type":"fill","source":"omt","source-layer":"water","paint":{"fill-color":"${p.water}"}}"""
        layers += """{"id":"waterway","type":"line","source":"omt","source-layer":"waterway","paint":{"line-color":"${p.water}","line-width":{"base":1.3,"stops":[[10,0.8],[16,5]]}}}"""
        layers += """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":14,"paint":{"fill-color":"${p.building}","fill-opacity":{"stops":[[14,0],[15,0.85]]}}}"""
        layers += road("path", """["in","class","path","track"]""", p.roadMinor, "[[13,0.6],[18,3]]", dash = "[2,2]", minzoom = 13)
        layers += road("rail", """["==","class","rail"]""", p.rail, "[[10,0.6],[18,3]]", dash = "[4,3]")
        layers += road("minor-casing", MINOR, p.roadCasing, "[[12,1.2],[14,4],[18,26]]", minzoom = 12)
        layers += road("major-casing", MAJOR, p.roadCasing, "[[6,1.5],[12,4.5],[14,8],[18,34]]")
        layers += road("minor", MINOR, p.roadMinor, "[[12,0.6],[14,2.4],[18,20]]", minzoom = 12)
        layers += road("major", MAJOR, p.roadMajor, "[[6,0.7],[12,2.6],[14,5],[18,26]]")
        if (options.streetNames) {
            layers += """{"id":"street-names","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":14,"filter":["in","class","minor","service","tertiary","secondary"],"layout":{"symbol-placement":"line","text-field":"{name}","text-font":["Noto Sans Regular"],"text-size":{"stops":[[14,10],[18,15]]},"text-letter-spacing":0.08},"paint":{"text-color":"${p.label}","text-halo-color":"${p.labelHalo}","text-halo-width":1.6}}"""
        }
        if (!options.forTraveler) {
            // Carte de l'hôte : les repères toponymiques sont indispensables pour préparer le parcours.
            layers += """{"id":"road-names","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":13,"layout":{"symbol-placement":"line","text-field":"{name}","text-font":["Noto Sans Regular"],"text-size":11},"paint":{"text-color":"${p.label}","text-halo-color":"${p.labelHalo}","text-halo-width":1.4}}"""
            layers += """{"id":"poi","type":"symbol","source":"omt","source-layer":"poi","minzoom":15,"filter":["<=","rank",12],"layout":{"text-field":"{name}","text-font":["Noto Sans Italic"],"text-size":10,"text-max-width":7},"paint":{"text-color":"${p.label}","text-opacity":0.8,"text-halo-color":"${p.labelHalo}","text-halo-width":1.2}}"""
            layers += """{"id":"place-small","type":"symbol","source":"omt","source-layer":"place","minzoom":9,"filter":["in","class","village","suburb","hamlet","neighbourhood"],"layout":{"text-field":"{name}","text-font":["Noto Sans Regular"],"text-size":{"stops":[[9,10],[15,14]]}},"paint":{"text-color":"${p.label}","text-halo-color":"${p.labelHalo}","text-halo-width":1.6}}"""
            layers += """{"id":"place-big","type":"symbol","source":"omt","source-layer":"place","filter":["in","class","city","town"],"layout":{"text-field":"{name}","text-font":["Noto Sans Bold"],"text-size":{"stops":[[4,10],[8,13],[14,19]]},"text-transform":"uppercase","text-letter-spacing":0.1},"paint":{"text-color":"${p.label}","text-halo-color":"${p.labelHalo}","text-halo-width":1.8}}"""
        }
        return """{"version":8,"name":"cap-mystere","glyphs":"$base/fonts/{fontstack}/{range}.pbf","sources":{"omt":{"type":"vector","url":"$base/planet","attribution":"© OpenStreetMap · OpenMapTiles · OpenFreeMap"}},"layers":[${layers.joinToString(",")}]}"""
    }

    private const val MAJOR = """["in","class","motorway","trunk","primary","secondary","tertiary"]"""
    private const val MINOR = """["in","class","minor","service"]"""

    private fun road(id: String, filter: String, color: String, stops: String, dash: String? = null, minzoom: Int = 5): String {
        val dashPart = if (dash != null) ""","line-dasharray":$dash""" else ""
        return """{"id":"road-$id","type":"line","source":"omt","source-layer":"transportation","minzoom":$minzoom,"filter":$filter,"layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"$color","line-width":{"base":1.4,"stops":$stops}$dashPart}}"""
    }
}
