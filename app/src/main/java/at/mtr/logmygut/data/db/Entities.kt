package at.mtr.logmygut.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagRow
import at.mtr.logmygut.domain.TagSource
import at.mtr.logmygut.domain.Threshold
import java.time.Instant
import java.time.LocalDate

// Data model from SPEC section 5. Foods are never deleted (only hidden), because meals reference them.

@Entity(
    tableName = "food",
    indices = [Index(value = ["seedKey"], unique = true), Index("kind"), Index("parentId")],
)
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: FoodKind,
    val name: String,
    val aliases: List<String> = emptyList(),
    val unit: FoodUnit,
    val unitLabel: String? = null,
    val note: String? = null,
    val builtIn: Boolean = false,
    val userModified: Boolean = false,
    val hidden: Boolean = false,
    /** The seed entry's original name; null for user-created foods. Never changes. */
    val seedKey: String? = null,
    /** Normalized name + aliases, for search (see [searchTextFor]). Set by [FoodDao] on every write, never by callers. */
    val searchText: String = "",
    /** Last time this food was logged in a meal; drives "recently used" ranking. */
    val lastUsedAt: Instant? = null,
    /** Ingredients only: the more general food this one inherits tags from (SPEC 4.4), e.g. Essig for Apfelessig. */
    val parentId: Long? = null,
    /**
     * Grams of an M portion, or of one piece for count units; shown next to amounts. Null if unknown.
     * For drinks it is millilitres instead.
     */
    val portionGrams: Double? = null,
    /** A drink: amounts show in ml and count toward the daily fluid total. Ingredients and dishes can be drinks. */
    @ColumnInfo(defaultValue = "0") val drink: Boolean = false,
    /** Built-in ingredients only: its tags no longer follow the seed, it only has [TagSource.USER] rows (SPEC 4). */
    @ColumnInfo(defaultValue = "0") val tagsDetached: Boolean = false,
)

/**
 * Whether this food's tags follow the seed: a built-in ingredient that isn't [detached][FoodEntity.tagsDetached]. Such a
 * food has [TagSource.SEED] rows plus [TagSource.USER] rows for the user's differences (SPEC 4).
 */
fun FoodEntity.tagsFollowSeed(): Boolean = builtIn && kind == FoodKind.INGREDIENT && !tagsDetached

/**
 * A tag on an ingredient. Both thresholds null = always applies.
 * [removed] cancels the same tag inherited from the parent food instead (thresholds unused then).
 * A food can have a seed row and a user row for the same tag; the user row wins (`ownRows` in the domain).
 */
@Entity(
    tableName = "food_tag",
    primaryKeys = ["foodId", "tag", "source"],
    foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["foodId"], onDelete = ForeignKey.CASCADE)],
)
data class FoodTagEntity(
    val foodId: Long,
    val tag: Tag,
    val minSize: PortionSize? = null,
    val minCount: Int? = null,
    @ColumnInfo(defaultValue = "0") val removed: Boolean = false,
    val source: TagSource,
)

/** The domain view of a stored tag row; all tag logic works on [TagRow] (SPEC 4.4). */
fun FoodTagEntity.toRow() = TagRow(
    tag = tag,
    threshold = minSize?.let { Threshold.MinSize(it) } ?: minCount?.let { Threshold.MinCount(it) },
    removed = removed,
    source = source,
)

fun TagRow.toEntity(foodId: Long = 0) = FoodTagEntity(
    foodId = foodId,
    tag = tag,
    minSize = (threshold as? Threshold.MinSize)?.size,
    minCount = (threshold as? Threshold.MinCount)?.n,
    removed = removed,
    source = source,
)

/** The saved recipe of a dish. */
@Entity(
    tableName = "dish_ingredient",
    primaryKeys = ["dishId", "ingredientId"],
    foreignKeys = [
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["dishId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["ingredientId"]),
    ],
    indices = [Index("ingredientId")],
)
data class DishIngredientEntity(val dishId: Long, val ingredientId: Long)

@Entity(tableName = "meal", indices = [Index("timestamp")])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val zoneId: String,
    val eatenOut: Boolean = false,
    val note: String? = null,
)

/** One logged item; exactly one of [count] / [size] is set, matching the food's unit. */
@Entity(
    tableName = "meal_item",
    foreignKeys = [
        ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["foodId"]),
    ],
    indices = [Index("mealId"), Index("foodId")],
)
data class MealItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val foodId: Long,
    val count: Int? = null,
    val size: PortionSize? = null,
)

/** For dish items only: the ingredients this meal actually had (recipe copy, then per-meal tweaks). */
@Entity(
    tableName = "meal_item_ingredient",
    primaryKeys = ["mealItemId", "ingredientId"],
    foreignKeys = [
        ForeignKey(entity = MealItemEntity::class, parentColumns = ["id"], childColumns = ["mealItemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["ingredientId"]),
    ],
    indices = [Index("ingredientId")],
)
data class MealItemIngredientEntity(val mealItemId: Long, val ingredientId: Long)

@Entity(tableName = "bowel_movement", indices = [Index("timestamp")])
data class BowelMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val zoneId: String,
    /** Bristol Stool Form Scale, 1..7. */
    val bristol: Int,
    val urgent: Boolean = false,
    /** Abdominal pain/cramps before the bowel movement. */
    val painBefore: Boolean = false,
    /** How the pain changed afterwards; set exactly when [painBefore] is true. */
    val painAfter: PainChange? = null,
    val note: String? = null,
)

@Entity(tableName = "symptom_entry", indices = [Index("timestamp")])
data class SymptomEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val zoneId: String,
    val type: SymptomType,
    /** 0..10 */
    val severity: Int,
    val note: String? = null,
)

@Entity(tableName = "medication_intake", indices = [Index("timestamp"), Index("name")])
data class MedicationIntakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val zoneId: String,
    val name: String,
    val effect: MedEffect? = null,
    val note: String? = null,
)

/** Each value is answered on its own (sleep in the morning, stress in the evening), so either may be missing. */
@Entity(tableName = "daily_check_in")
data class DailyCheckInEntity(
    @PrimaryKey val date: LocalDate,
    /** 1..5 */
    val stress: Int? = null,
    /** Sleep quality of the night before, 1..5 */
    val sleep: Int? = null,
)
