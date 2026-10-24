package at.mtr.logmygut.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A meal item together with its food (ingredient or dish). */
data class MealItemWithFood(
    @Embedded val item: MealItemEntity,
    @Relation(parentColumn = "foodId", entityColumn = "id") val food: FoodEntity,
)

/** A meal with its items, loaded in one Room transaction. Items are not ordered; sort by item id. */
data class MealWithItems(
    @Embedded val meal: MealEntity,
    @Relation(entity = MealItemEntity::class, parentColumn = "id", entityColumn = "mealId")
    val items: List<MealItemWithFood>,
)
