package com.hoshino.wenku8reader.data

import androidx.compose.runtime.Immutable

/** One book-review topic in a Wenku8 book's review list. */
@Immutable
data class BookReview(
    val id: Int,
    val title: String,
    val replyCount: Int,
    val viewCount: Int,
)

/** One page of review topics. */
@Immutable
data class BookReviewPage(
    val bookId: Int,
    val bookTitle: String,
    val reviews: List<BookReview>,
    val page: Int,
    val totalPages: Int,
)

/** A post in a review thread. The first post is the topic and the rest are replies. */
@Immutable
data class ReviewPost(
    val id: String,
    val floor: Int,
    val author: String,
    val postedAt: String,
    val body: String,
)

/** One page of a review topic and its replies. */
@Immutable
data class ReviewThreadPage(
    val reviewId: Int,
    val bookTitle: String,
    val title: String,
    val posts: List<ReviewPost>,
    val page: Int,
    val totalPages: Int,
)
