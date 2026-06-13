package com.candidstickers.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.candidstickers.data.CandidCrop
import com.candidstickers.data.CropDb
import com.candidstickers.data.PersonRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * People tab state: person cards, the currently-open person's crops, renames.
 * Enrichment (clustering) is wired elsewhere; [refresh] just re-queries the DB,
 * so calling it on tab switch picks up whatever the workers have produced.
 */
class PeopleViewModel(app: Application) : AndroidViewModel(app) {

    private val db = CropDb.getInstance(app)

    var persons by mutableStateOf<List<PersonRow>>(emptyList())
        private set

    /** Non-null while a person's crops grid is open. */
    var openPerson by mutableStateOf<PersonRow?>(null)
        private set

    var personCrops by mutableStateOf<List<CandidCrop>>(emptyList())
        private set

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { db.persons() }
            persons = rows
            val open = openPerson ?: return@launch
            val updated = rows.find { it.id == open.id }
            openPerson = updated
            personCrops =
                if (updated == null) emptyList()
                else withContext(Dispatchers.IO) { db.cropsForPerson(updated.id) }
        }
    }

    fun open(person: PersonRow) {
        openPerson = person
        personCrops = emptyList()
        viewModelScope.launch {
            personCrops = withContext(Dispatchers.IO) { db.cropsForPerson(person.id) }
        }
    }

    fun closePerson() {
        openPerson = null
        personCrops = emptyList()
    }

    fun rename(personId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.renamePerson(personId, trimmed) }
            refresh()
        }
    }

    /** Display name for a crop's person, or null when unknown/not yet clustered. */
    fun nameFor(personId: Long?): String? = nameFor(persons, personId)

    companion object {
        /** "Person N" keeps cards labeled before the user renames anyone. */
        fun displayName(person: PersonRow): String =
            person.name?.takeIf { it.isNotBlank() } ?: "Person ${person.id}"

        fun nameFor(persons: List<PersonRow>, personId: Long?): String? {
            if (personId == null) return null
            return persons.find { it.id == personId }?.let(::displayName)
        }
    }
}
