package com.example.ui.achievements

data class AchievementDefinition(
    val key: String,
    val title: String,
    val description: String,
    val category: String,
    val xpReward: Int,
    val targetValue: Int = 1
)

data class AchievementWithStatus(
    val definition: AchievementDefinition,
    val isUnlocked: Boolean,
    val unlockedAt: Long? = null,
    val currentValue: Int = 0
) {
    val progressPercent: Int
        get() = if (isUnlocked) 100
        else if (definition.targetValue <= 0) 0
        else ((currentValue.coerceIn(0, definition.targetValue) * 100) / definition.targetValue)
}

data class BadgeDefinition(
    val key: String,
    val title: String,
    val subtitle: String,
    val requirementText: String,
    val accentHex: String = "#7C4DFF"
)

data class BadgeWithStatus(
    val definition: BadgeDefinition,
    val isUnlocked: Boolean,
    val unlockedAt: Long? = null
)

/**
 * Deterministic Level and XP progression calculator.
 * - Level 1: 0 .. 99 XP
 * - Level 2: 100 .. 199 XP
 * - Level N: (N - 1) * 100 .. (N * 100) - 1 XP
 */
object LevelProgressionEngine {
    const val XP_PER_LEVEL = 100

    fun calculateLevel(totalXp: Int): Int {
        val safeXp = totalXp.coerceAtLeast(0)
        return 1 + (safeXp / XP_PER_LEVEL)
    }

    fun xpIntoCurrentLevel(totalXp: Int): Int {
        val safeXp = totalXp.coerceAtLeast(0)
        return safeXp % XP_PER_LEVEL
    }

    fun xpRemainingForNextLevel(totalXp: Int): Int {
        return XP_PER_LEVEL - xpIntoCurrentLevel(totalXp)
    }

    fun rankTitleForLevel(level: Int): String {
        return when {
            level >= 20 -> "Grandmaster Architect"
            level >= 15 -> "Principal Algorithmist"
            level >= 10 -> "Staff Cyber Engineer"
            level >= 7 -> "Senior Quest Solver"
            level >= 4 -> "Rising Code Knight"
            level >= 2 -> "Apprentice Developer"
            else -> "Novice Coder"
        }
    }
}

object GamificationCatalog {

    // Achievement Keys
    const val ACH_FIRST_SOLVE = "ACH_FIRST_SOLVE"
    const val ACH_SOLVE_5 = "ACH_SOLVE_5"
    const val ACH_SOLVE_10 = "ACH_SOLVE_10"
    const val ACH_SOLVE_50 = "ACH_SOLVE_50"
    const val ACH_SOLVE_100 = "ACH_SOLVE_100"
    const val ACH_FIRST_DAILY_CHALLENGE = "ACH_FIRST_DAILY_CHALLENGE"
    const val ACH_STREAK_3 = "ACH_STREAK_3"
    const val ACH_STREAK_7 = "ACH_STREAK_7"
    const val ACH_TOPIC_COMPLETION = "ACH_TOPIC_COMPLETION"
    const val ACH_HIGH_ACCURACY = "ACH_HIGH_ACCURACY"
    const val ACH_XP_100 = "ACH_XP_100"
    const val ACH_XP_500 = "ACH_XP_500"

    // Badge Keys
    const val BADGE_CODE_INITIATE = "BADGE_CODE_INITIATE"
    const val BADGE_BUG_HUNTER = "BADGE_BUG_HUNTER"
    const val BADGE_CONCEPT_SCHOLAR = "BADGE_CONCEPT_SCHOLAR"
    const val BADGE_EASY_CONQUEROR = "BADGE_EASY_CONQUEROR"
    const val BADGE_MEDIUM_TACTICIAN = "BADGE_MEDIUM_TACTICIAN"
    const val BADGE_HARD_LEGEND = "BADGE_HARD_LEGEND"
    const val BADGE_STREAK_GUARDIAN = "BADGE_STREAK_GUARDIAN"
    const val BADGE_POLYGLOT = "BADGE_POLYGLOT"
    const val BADGE_TOPIC_CHAMPION = "BADGE_TOPIC_CHAMPION"

    val ALL_ACHIEVEMENTS: List<AchievementDefinition> = listOf(
        AchievementDefinition(
            key = ACH_FIRST_SOLVE,
            title = "First Problem Solved",
            description = "Verify your very first coding solution.",
            category = "Problem Solving",
            xpReward = 25,
            targetValue = 1
        ),
        AchievementDefinition(
            key = ACH_SOLVE_5,
            title = "5 Problems Solved",
            description = "Solve 5 unique coding problems across the Question Bank.",
            category = "Problem Solving",
            xpReward = 50,
            targetValue = 5
        ),
        AchievementDefinition(
            key = ACH_SOLVE_10,
            title = "10 Problems Solved",
            description = "Solve 10 unique coding problems across any topics.",
            category = "Problem Solving",
            xpReward = 100,
            targetValue = 10
        ),
        AchievementDefinition(
            key = ACH_SOLVE_50,
            title = "50 Problems Solved",
            description = "Solve 50 unique coding problems.",
            category = "Problem Solving",
            xpReward = 250,
            targetValue = 50
        ),
        AchievementDefinition(
            key = ACH_SOLVE_100,
            title = "100 Problems Solved",
            description = "Reach 100 verified unique problem solutions.",
            category = "Problem Solving",
            xpReward = 500,
            targetValue = 100
        ),
        AchievementDefinition(
            key = ACH_FIRST_DAILY_CHALLENGE,
            title = "First Daily Challenge",
            description = "Complete your first Daily Challenge problem.",
            category = "Daily Quests",
            xpReward = 40,
            targetValue = 1
        ),
        AchievementDefinition(
            key = ACH_STREAK_3,
            title = "3-Day Streak",
            description = "Maintain a 3-day streak by completing at least 3 daily goals per day.",
            category = "Streaks",
            xpReward = 50,
            targetValue = 3
        ),
        AchievementDefinition(
            key = ACH_STREAK_7,
            title = "7-Day Streak",
            description = "Maintain a 7-day coding streak.",
            category = "Streaks",
            xpReward = 120,
            targetValue = 7
        ),
        AchievementDefinition(
            key = ACH_TOPIC_COMPLETION,
            title = "Topic Completion",
            description = "Reach 10/10 verified solves in any of the 8 core learning topics.",
            category = "Mastery",
            xpReward = 150,
            targetValue = 10
        ),
        AchievementDefinition(
            key = ACH_HIGH_ACCURACY,
            title = "High Accuracy",
            description = "Maintain at least 80% accuracy across 5 or more submissions.",
            category = "Precision",
            xpReward = 75,
            targetValue = 80
        ),
        AchievementDefinition(
            key = ACH_XP_100,
            title = "100 XP Milestone",
            description = "Earn at least 100 total XP from coding quests and goals.",
            category = "XP Milestones",
            xpReward = 20,
            targetValue = 100
        ),
        AchievementDefinition(
            key = ACH_XP_500,
            title = "500 XP Milestone",
            description = "Earn at least 500 total XP.",
            category = "XP Milestones",
            xpReward = 50,
            targetValue = 500
        )
    )

    val ALL_BADGES: List<BadgeDefinition> = listOf(
        BadgeDefinition(
            key = BADGE_CODE_INITIATE,
            title = "Code Initiate",
            subtitle = "Daily Quest Starter",
            requirementText = "Complete at least 1 Daily Goal"
        ),
        BadgeDefinition(
            key = BADGE_BUG_HUNTER,
            title = "Bug Hunter",
            subtitle = "Mistake Reviewer",
            requirementText = "Review a problem from your Mistakes Notebook"
        ),
        BadgeDefinition(
            key = BADGE_CONCEPT_SCHOLAR,
            title = "Concept Scholar",
            subtitle = "Theory Master",
            requirementText = "Pass the interactive 3-question Concept Quiz"
        ),
        BadgeDefinition(
            key = BADGE_EASY_CONQUEROR,
            title = "Easy Conqueror",
            subtitle = "Foundation Builder",
            requirementText = "Solve 3 Easy problems"
        ),
        BadgeDefinition(
            key = BADGE_MEDIUM_TACTICIAN,
            title = "Medium Tactician",
            subtitle = "Algorithm Specialist",
            requirementText = "Solve 3 Medium problems"
        ),
        BadgeDefinition(
            key = BADGE_HARD_LEGEND,
            title = "Hard Legend",
            subtitle = "Elite Solver",
            requirementText = "Solve 1 Hard problem"
        ),
        BadgeDefinition(
            key = BADGE_STREAK_GUARDIAN,
            title = "Streak Guardian",
            subtitle = "Consistent Learner",
            requirementText = "Reach a 3-day coding streak"
        ),
        BadgeDefinition(
            key = BADGE_POLYGLOT,
            title = "Polyglot Coder",
            subtitle = "Multi-Language",
            requirementText = "Practice or submit code in at least 2 languages"
        ),
        BadgeDefinition(
            key = BADGE_TOPIC_CHAMPION,
            title = "Topic Master",
            subtitle = "10/10 Path Finisher",
            requirementText = "Reach 10/10 in any learning topic"
        )
    )
}
