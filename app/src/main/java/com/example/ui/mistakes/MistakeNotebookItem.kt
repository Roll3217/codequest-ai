package com.example.ui.mistakes

data class MistakeNotebookItem(
    val id: Long,
    val questionId: String,
    val title: String,
    val difficulty: String,
    val topic: String,
    val savedDateFormatted: String
)
