package com.example.data.local

import com.example.model.QuestionEntity
import org.json.JSONArray
import org.json.JSONObject

object QuestionBankSeed {

    fun buildStarterCodeJson(functionName: String, paramsDesc: String, returnTypeDesc: String): String {
        val obj = JSONObject()
        obj.put(
            "Kotlin",
            """
            // CodeQuest AI Workspace — Kotlin
            fun $functionName($paramsDesc): $returnTypeDesc {
                // Write your solution here
                TODO("Implement $functionName")
            }

            fun main() {
                // Read standard input and invoke $functionName
            }
            """.trimIndent()
        )
        obj.put(
            "Java",
            """
            // CodeQuest AI Workspace — Java
            import java.util.*;

            public class Solution {
                public static void solve(Scanner sc) {
                    // Implement $functionName logic here
                }

                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    solve(sc);
                }
            }
            """.trimIndent()
        )
        obj.put(
            "Python",
            """
            # CodeQuest AI Workspace — Python 3
            import sys

            def $functionName():
                # Write your solution here
                pass

            if __name__ == "__main__":
                $functionName()
            """.trimIndent()
        )
        obj.put(
            "C++",
            """
            // CodeQuest AI Workspace — C++17
            #include <iostream>
            #include <vector>
            #include <string>
            using namespace std;

            int main() {
                ios::sync_with_stdio(false);
                cin.tie(nullptr);
                // Write your solution for $functionName here
                return 0;
            }
            """.trimIndent()
        )
        obj.put(
            "C",
            """
            // CodeQuest AI Workspace — C11
            #include <stdio.h>
            #include <stdlib.h>

            int main(void) {
                // Write your solution for $functionName here
                return 0;
            }
            """.trimIndent()
        )
        obj.put(
            "JavaScript",
            """
            // CodeQuest AI Workspace — JavaScript (Node.js)
            function $functionName(input) {
                // Write your solution here
                return "";
            }
            """.trimIndent()
        )
        return obj.toString()
    }

    fun getStarterCodeForLanguage(starterCodeJson: String, language: String): String {
        return try {
            val json = JSONObject(starterCodeJson)
            json.optString(language, json.optString("Kotlin", "// Write your code here"))
        } catch (_: Exception) {
            "// Write your solution here"
        }
    }

    @Suppress("LongParameterList")
    fun q(
        id: String,
        title: String,
        description: String,
        topic: String,
        subtopic: String,
        difficulty: String,
        ex1In: String,
        ex1Out: String,
        ex2In: String,
        ex2Out: String,
        constraints: String,
        expectedInput: String,
        expectedOutput: String,
        tagsCsv: String,
        timeComplexity: String,
        spaceComplexity: String,
        functionName: String,
        paramsDesc: String,
        returnTypeDesc: String,
        solutionCode: String
    ): QuestionEntity {
        val testCasesArray = JSONArray().apply {
            put(JSONObject().put("in", ex1In).put("out", ex1Out))
            put(JSONObject().put("in", ex2In).put("out", ex2Out))
        }

        return QuestionEntity(
            id = id,
            title = title,
            description = description,
            topic = topic,
            subtopic = subtopic,
            difficulty = difficulty,
            examplesJson = "Example 1:\nInput: $ex1In\nOutput: $ex1Out\n\nExample 2:\nInput: $ex2In\nOutput: $ex2Out",
            constraintsText = "• $constraints\n• Target Time Complexity: $timeComplexity\n• Target Space Complexity: $spaceComplexity",
            expectedInput = expectedInput,
            expectedOutput = expectedOutput,
            tagsCsv = tagsCsv,
            testCasesJson = testCasesArray.toString(),
            hintsJson = "1. Focus on $subtopic techniques in $topic.\n2. Parse input format: $expectedInput\n3. Respect constraints ($constraints) and format output as: $expectedOutput",
            approachText = "Use $subtopic principles to solve '$title' cleanly with $timeComplexity time complexity and $spaceComplexity space complexity.",
            complexityText = "Time Complexity: $timeComplexity\nSpace Complexity: $spaceComplexity",
            starterCodeJson = buildStarterCodeJson(functionName, paramsDesc, returnTypeDesc),
            solutionsJson = solutionCode
        )
    }

    /**
     * Complete 240-Question Bank across all 8 core topics:
     * - 8 Topics × 30 Questions per Topic = 240 Questions
     * - Each Topic contains 10 Easy, 10 Medium, and 10 Hard questions
     * - Total: 80 Easy, 80 Medium, 80 Hard
     */
    val INITIAL_QUESTIONS: List<QuestionEntity> by lazy {
        QuestionBankTopics1And2.QUESTIONS +
            QuestionBankTopics3And4.QUESTIONS +
            QuestionBankTopics5And6.QUESTIONS +
            QuestionBankTopics7And8.QUESTIONS
    }
}
