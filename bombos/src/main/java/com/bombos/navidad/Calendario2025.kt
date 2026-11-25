package com.bombos.navidad

/**
 * HORAS REALES DE LOS PREMIOS MAYORES DEL SORTEO DE NAVIDAD 2025 (hora peninsular), según la prensa del
 * 22/12/2025 (RTVE pool, Europa Press, EFE, El Diario, Telecinco, RTVC, Noticias de Navarra…).
 * Con la opción activada, el premio sale del bombo de premios en cuanto el reloj del sorteo llega a esa hora,
 * y le toca al número que salga en ese momento del bombo grande. El resto de bolas son pedrea.
 */
object Calendario2025 {
    data class Hito(val segundo: Int, val cat: Cat, val nota: String)

    private fun h(hm: String) = hm.substringBefore(':').toInt() * 3600 + hm.substringAfter(':').toInt() * 60

    val HITOS: List<Hito> = listOf(
        Hito(h("09:20"), Cat.SEGUNDO, "2º (70.048) · 1ª tabla"),
        Hito(h("09:53"), Cat.CUARTO, "4º (78.477) · tabla 2, alambre 7"),
        Hito(h("10:34"), Cat.QUINTO, "5º (23.112) · tabla 4, alambre 1"),
        Hito(h("10:40"), Cat.QUINTO, "5º (60.649) · tabla 4, alambre 3"),
        Hito(h("10:44"), Cat.GORDO, "Gordo (79.432) · tabla 4, alambre 4"),
        Hito(h("11:03"), Cat.QUINTO, "5º (77.715) · pasadas las 11:00 (hora aproximada)"),
        Hito(h("11:12"), Cat.TERCERO, "3º (90.693) · tabla 5, alambre 4"),
        Hito(h("11:46"), Cat.QUINTO, "5º (25.412) · tabla 6, alambre 5"),
        Hito(h("11:48"), Cat.QUINTO, "5º (61.366) · tabla 6"),
        Hito(h("12:24"), Cat.QUINTO, "5º (94.273) · tabla 7"),
        Hito(h("12:36"), Cat.CUARTO, "4º (25.508) · tabla 8, alambre 1"),
        Hito(h("13:22"), Cat.QUINTO, "5º (41.716) · tabla 9 (hora aproximada)"),
        Hito(h("13:27"), Cat.QUINTO, "5º (18.669) · último premio mayor"),
    )

    fun hora(s: Int) = "%02d:%02d".format(s / 3600, (s / 60) % 60)
}
