package se.racelink.studio

data class ChartRun(val attempt: Attempt, val number: Int)
fun adjacentSkier(athletes: List<Athlete>, selected: Int, direction: Int): Int {
    val index = athletes.indexOfFirst { it.id == selected }
    if (index < 0 || athletes.isEmpty()) return selected
    return athletes[(index + direction.coerceIn(-1, 1)).coerceIn(0, athletes.lastIndex)].id
}
fun chartRuns(runs: List<Attempt>, taggedOnly: Boolean) = runs.mapIndexed { index, run -> ChartRun(run, index + 1) }
    .filter { !taggedOnly || it.attempt.tags.isNotEmpty() }
data class ChartPoint(val x: Float, val y: Float)
fun chartPage(runs: List<ChartRun>, page: Int, pageSize: Int = 12): List<ChartRun> {
    require(pageSize in 1..12)
    val last = (runs.size - 1).coerceAtLeast(0) / pageSize
    return runs.drop(page.coerceIn(0, last) * pageSize).take(pageSize)
}
fun chartHit(points: List<ChartPoint>, x: Float, y: Float, radius: Float): Int? = points.indices
    .minByOrNull { i -> (points[i].x - x) * (points[i].x - x) + (points[i].y - y) * (points[i].y - y) }
    ?.takeIf { i -> (points[i].x - x) * (points[i].x - x) + (points[i].y - y) * (points[i].y - y) <= radius * radius }
