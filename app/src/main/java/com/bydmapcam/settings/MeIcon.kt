package com.bydmapcam.settings

import androidx.annotation.DrawableRes
import com.bydmapcam.R

/**
 * What marks the car on the map. The arrow is the honest default — it shows heading and nothing
 * else — while the vehicle shapes read as "that's me" at a glance on a big dash screen.
 *
 * The shapes are generic on purpose: a carmaker's own silhouette is their artwork, and at this size
 * only the class of vehicle reads anyway.
 */
enum class MeIcon(val label: String, val imageId: String, @DrawableRes val res: Int) {
    ARROW("ลูกศร", "me_arrow", R.drawable.ic_me_arrow),
    SUV("รถ SUV", "me_suv", R.drawable.ic_me_car_suv),
    SEDAN("รถเก๋ง", "me_sedan", R.drawable.ic_me_car_sedan),
    SPORT("รถสปอร์ต", "me_sport", R.drawable.ic_me_car_sport)
}
