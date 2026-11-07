package com.aryan.reader.shared.ui

import androidx.compose.runtime.compositionLocalOf

/** Fewest / most books per row the user can pick for the library grid. */
const val LibraryColumnsMin: Int = 1
const val LibraryColumnsMax: Int = 4
const val LibraryColumnsDefault: Int = 2

/**
 * Books per row on phones, chosen by the user (1 = wide list cards with lots of title text,
 * 2-4 = cover grid). Larger screens add columns on top of this number.
 */
val LocalLibraryColumns = compositionLocalOf { LibraryColumnsDefault }
