package se.racelink.studio

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class DraftStateTest {
    private val scope = SaverScope { true }
    @Test fun tagDraftRetainsEditedNamesCategoriesAndNewTags() {
        val draft = demoTraining().tags.map { it.copy(name = it.name + " Edited") } + CoachingTag(99, "New", TagCategory.CHANGE)
        val saved = with(TagListSaver) { scope.save(draft) }!!
        assertEquals(draft, TagListSaver.restore(saved))
    }
    @Test fun selectedTagAndParticipantDraftsRoundTripIncludingEmptySelection() {
        listOf(emptySet(), setOf(1, 3, 100)).forEach { draft ->
            val saved = with(IdSetSaver) { scope.save(draft) }!!
            assertEquals(draft, IdSetSaver.restore(saved))
        }
    }
}
