package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class SkierPickerTest {
    private val skiers = listOf(Athlete(1, 12, "Alma Elisabeth Bergström"), Athlete(2, 24, "Elias Åberg"), Athlete(3, 31, "Maja"))
    @Test fun searchesNameOrBibWithoutChangingIdentityOrOrder() {
        assertEquals(listOf(skiers[1]), findSkiers(skiers, " åBERG "))
        assertEquals(listOf(skiers[0]), findSkiers(skiers, "12"))
        assertEquals(listOf(skiers[0], skiers[2]), findSkiers(skiers, "1"))
        assertEquals(skiers, findSkiers(skiers, "  "))
        assertTrue(findSkiers(skiers, "999").isEmpty())
    }
    @Test fun searchingRestrictedParticipantsDoesNotAddInactiveSkiers() {
        assertTrue(findSkiers(skiers.filter { it.id != 2 }, "Elias").isEmpty())
    }
}
