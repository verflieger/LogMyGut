package at.mtr.logmygut.data.db

import androidx.room.TypeConverter
import at.mtr.logmygut.domain.Tag
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json

/**
 * Room column conversions. Other enums are stored by name (Room's default);
 * [Tag] uses its stable key so it matches the seed files.
 */
class Converters {
    @TypeConverter fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun dateToString(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter fun tagToKey(value: Tag?): String? = value?.key
    @TypeConverter fun keyToTag(value: String?): Tag? = value?.let(Tag::fromKey)

    @TypeConverter fun listToJson(value: List<String>): String = Json.encodeToString(value)
    @TypeConverter fun jsonToList(value: String): List<String> = Json.decodeFromString(value)
}
