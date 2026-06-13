package com.candidstickers.ui

import com.candidstickers.data.PersonRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plain JVM test for the People tab's pure naming logic. */
class PersonNameTest {

    private fun person(id: Long, name: String? = null) =
        PersonRow(id = id, name = name, faceCount = 3, coverCropPath = null)

    @Test
    fun displayNameUsesStoredName() {
        assertEquals("Mum", PeopleViewModel.displayName(person(4, "Mum")))
    }

    @Test
    fun displayNameFallsBackToPersonId() {
        assertEquals("Person 4", PeopleViewModel.displayName(person(4)))
    }

    @Test
    fun blankNameFallsBackToPersonId() {
        assertEquals("Person 7", PeopleViewModel.displayName(person(7, "   ")))
    }

    @Test
    fun nameForNullPersonIdIsNull() {
        assertNull(PeopleViewModel.nameFor(listOf(person(1, "Mum")), null))
    }

    @Test
    fun nameForUnknownPersonIsNull() {
        assertNull(PeopleViewModel.nameFor(listOf(person(1, "Mum")), 99L))
    }

    @Test
    fun nameForResolvesNamedAndUnnamedPersons() {
        val persons = listOf(person(1, "Mum"), person(2))
        assertEquals("Mum", PeopleViewModel.nameFor(persons, 1L))
        assertEquals("Person 2", PeopleViewModel.nameFor(persons, 2L))
    }
}
