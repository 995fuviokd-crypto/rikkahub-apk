package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FilterLogic {
    @SerialName("and_any") AND_ANY,
    @SerialName("and_all") AND_ALL,
    @SerialName("not_any") NOT_ANY,
    @SerialName("not_all") NOT_ALL,
}
