# Vellum – Übergabe an den nächsten Agenten

Stand: Schritt 1 (Backend/Datenmodell, DB v41) auf dem v40-Original NEU aufgebaut (die ursprünglichen Schritt-1-Dateien lagen nicht vor). **Nichts davon wurde kompiliert oder getestet** (keine Gradle-/Netzwerk-Umgebung im Container). Erster Schritt im nächsten Lauf: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` und alle Fehler beheben.

## Bestand vor meiner Arbeit (wiederverwenden, nicht ersetzen)
- Room v40: `tags` (type SYSTEM/CUSTOM, trackingEnabled, visibleInNavigation, sortOrder, icon, description), `book_tag_cross_ref`, `reading_sessions`, `tag_tracking_events`.
- `data/ReadingTracker.kt`: prozentbasierte Reading/Finished-Automatik, Wiederlesen, Audit-Log. Aufruf aus `RecentFilesRepository.updateEpubReadingPosition` / `updatePdfReadingPosition` (Position wird dort schon bei jedem Update in Room geschrieben).
- Reihen existieren nur als `recent_files.seriesName/seriesIndex` (+ `AuthorSeriesShelves.kt`).
- UI: `TagTrackingSettingsScreen.kt`, `UnifiedLibraryTags.kt` (Drawer), `UnifiedLibraryTagEditor.kt`, `MainViewModel.kt` (~10k Zeilen, nicht erweitern).

## Erledigt (Dateien)
- `data/LibraryEntities.kt`: TagEntity neu: `isEnabled`, `showIcon`, `showDescription`, `showCount`; `visibleInNavigation` Default jetzt **false**.
- `data/VellumEntities.kt`: `series`, `series_tag_assignments` (inheritMode SERIES_ONLY/EXISTING/EXISTING_AND_FUTURE, isAutomatic), `book_tag_inherited`, `read_positions` (PK bookId+positionKey = keine Doppelzählung), `book_read_stats`, `book_work_info` (coversWholeWork); `seriesKeyOf()`.
- `data/VellumDaos.kt`: `SeriesDao`, `ReadPositionDao`; `LibraryDaos.kt`: `setEnabled`, `setDisplayOptions`.
- `data/AppDatabase.kt`: Version 41, `MIGRATION_40_41` (bestehende Daten bleiben, bestehende eigene Tags bleiben sichtbar), DAOs registriert.
- `data/VellumRules.kt` (reine Logik): `SeriesProgressCalculator`, `TagInheritancePlanner`, `ReadPageGate` (Mindestsichtzeit, Standard 4 s, konfigurierbar), Schlüssel `p:<seite>` (PDF) / `c<kapitel>:b<block>` (EPUB, schriftgrößenunabhängig).
- `data/VellumRepositories.kt`: `TagRepository`, `SeriesRepository` (inkl. `reconcile()` + `startReconciler`), `ReadingPositionRecorder` (transaktionale Sofortspeicherung), `ReadingStatusTracker` (Reihenstatus; „Gelesen“ nur bei als abgeschlossen markierter Reihe).
- `ReadingTracker.kt`: neue Einstellungen (`autoAssignReading/Finished`, `minPageDwellMs`), „Wird gelesen“ erst nach ≥1 bestätigter Seite, kein automatisches „Gelesen“ für Dateien mit `coversWholeWork=false`, respektiert `isEnabled`.
- `RecentFilesRepository.kt`: Recorder + StatusTracker an beiden Positions-Updates angebunden; Reconciler wird im `init` gestartet.
- `UnifiedLibraryTags.kt`: Drawer zeigt **alle** Tags (System und eigene) nur bei `visibleInNavigation && isEnabled`.
- `AndroidLibraryMutationStore.createCustomTag`: neuer Parameter `visibleInNavigation` (Default false).
- Tests: `VellumRulesTest.kt` (Reihenfortschritt, Vererbung, Gate), `VellumDaoTest.kt` (Doppelzählung, Default hidden).

## Offen
1. **Kompilieren/Testen** (siehe oben); ggf. `Locator.chapterIndex`-Typ, DAO-Rückgabetypen (`SeriesVolumeRow` mit EXISTS-Booleans), Room-Schema-Validierung der Migration gegen die Entities prüfen.
2. **UI Phase 2**: zentrale Tag-Verwaltung (System/Eigene getrennt, scrollbar: Symbol/Farbe, Name, Anzahl, Aktiv, Seitenleiste, Automatik); Schalter pro Tag für `isEnabled`, `showIcon/Description/Count`; „In Seitenleiste anzeigen“ im Editor (`UnifiedLibraryTagEditor`) beim Erstellen und Bearbeiten (Parameter existiert im Store, UI fehlt); Auto-Zuordnung-Schalter aller 5 System-Tags (aktuell nur Reading/Finished über `trackingEnabled`); neue Strings (de/en); Einstellungsschalter für `autoAssignReading/Finished`, Mindestsichtzeit.
3. **System-Tags nach Spec**: Namen/Symbole (Favoriten, Gelesen, Wird gelesen, Nachschlagewerk, Leseliste) prüfen; `showIcon/showDescription/showCount` im Drawer/Listen auswerten.
4. **Effektive Tags in der UI**: Buch-Projektion (`RecentFileItem.tags`, `AndroidLibraryModelMappers`, Zähler `tagBookCounts`) nutzt noch nur `book_tag_cross_ref`; auf `SeriesDao.observeEffectiveBookTags()` bzw. `TagRepository.observeEffectiveBookTags()` umstellen, damit Vererbtes angezeigt wird. Direkt vs. geerbt kennzeichnen.
5. **Phase 3 UI**: Reihe einem Tag zuordnen mit Auswahl (nur Reihe / vorhandene Bände / vorhandene + zukünftige) -> `TagRepository.assignToSeries`; Entfernen -> `removeFromSeries`; Schlagwortansicht gruppiert Reihen als aufklappbaren Eintrag („Overgeared – 5 Bände“); Reihenfortschritt/Status-Text („Alle aktuell veröffentlichten Bände gelesen“) anzeigen; UI zum Setzen von `knownVolumeCount`, `isStoryComplete`, `coversWholeWork`.
6. **Lesetracker**: `ReadingPositionRecorder.flush()` bei Reader-Pause/Schließen aufrufen; PDF-Seitenzahl als `totalUnits` übergeben; Datei-Fortschritt (Seiten/Positionen) vs. Werk-Fortschritt in der UI trennen; EPUB-Fortschritt bleibt prozentbasiert, die Blockzählung ist nur Bestätigungsnachweis. Hinweis: Gate-Zustand (unbestätigte Kandidaten) liegt nur im Speicher, bestätigte Positionen nie.
7. Offene Punkte der Statusautomatik: Reihen-Tags werden nach manuellem Entfernen bei der nächsten Aktivität wieder gesetzt (keine Edge-Triggerung wie im Buch-Tracker); Wiederlesen-Logik für Reihen; Einstellung „Gelesen beim Wiederlesen beibehalten/zurücksetzen“ nur auf Buchebene umgesetzt.
8. ERLEDIGT: `insertTag`/`insertTags` sind jetzt IGNORE; `createTag` nutzt `TagRepository.createOrUpdateNameAndColor` (UPDATE statt REPLACE).
9. Backup/Sync (`BackupManager`, Cloud-Sync) kennen die neuen Tabellen/Spalten noch nicht.
10. Pflichttestplan (Abschnitt 10 der Spec) manuell auf Android: Schlagwörter, Reihen, Lesetracker, Prozess-Kill, Schriftgröße; Instrumented-Tests für Migration 40→41, Parallelität und Prozessabbruch; Release-Build.

## Regeln aus der Spec (nicht verletzen)
MainViewModel nicht weiter vergrößern; DB ist Quelle der Wahrheit; keine bestehenden Zuordnungen löschen; manuelle Zuordnungen nie überschreiben; keine falsche Fertigmeldung bei unvollständigen Reihen/Teil-EPUBs.

## Hinweise zum Neuaufbau
- `TagEntity.visibleInNavigation`: Kotlin-Default `false`, SQL-Default bleibt `1` (muss zu MIGRATION_39_40 passen, sonst schlägt die Room-Schema-Validierung fehl). Bestehende Tags behalten ihren Wert.
- `ReadingTracker`: „Wird gelesen“ jetzt nach ≥1 bestätigter Seite (statt +3 %); `coversWholeWork=false` verhindert automatisches „Gelesen“.
- `RecentFilesRepository.flushReadingPosition(bookId)` existiert, wird aber noch von keinem Reader aufgerufen (Punkt 6).
- Neue Tests: `VellumRulesTest`, `VellumDaoTest`, `VellumRepositoryTest`, Ergänzung in `ReadingTrackingRulesTest`. Nichts davon wurde ausgeführt.

## Phase 2 UI (Stand 2026-11-28, ebenfalls ungetestet)
- `TagTrackingSettingsScreen`: zentrale Verwaltung (System getrennt von eigenen Tags, scrollbar). Pro Tag: Seitenleiste-Schalter, aufklappbar Aktiviert / Symbol / Beschreibung / Anzahl; eigene Tags mit „Bearbeiten“. Globale Schalter „Wird gelesen“/„Gelesen“ automatisch und Mindestsichtzeit (2/4/8 s).
- `TagEditorDialog`: Schalter Aktiviert, In Seitenleiste anzeigen, Anzahl anzeigen (beim Erstellen und Bearbeiten). Neue Tags: standardmäßig ausgeblendet.
- Drawer/Detail-Header werten `showIcon/showDescription/showCount` aus.
- `AndroidLibraryMutationStore.tagCrossRefsFlow` liefert jetzt direkte + geerbte Tags (`observeEffectiveBookTags`), damit Zähler/Filter Vererbtes sehen. Noch nicht gekennzeichnet (direkt vs. geerbt), Entfernen eines nur geerbten Tags am Band ist ein No-op.
- Neue Strings nur in values (EN) und values-de; andere Sprachen fallen auf EN zurück (ggf. Lint MissingTranslation prüfen).
- Einzelne Auto-Zuordnung pro System-Tag außer Reading/Finished (Spec 2.2) fehlt weiterhin, da nur diese zwei automatisch sind.
- Offen: Phase 3 UI (Reihe einem Tag zuordnen mit Modus-Auswahl, gruppierte Reihenansicht, Fortschrittstext, UI für knownVolumeCount/isStoryComplete/coversWholeWork), `flushReadingPosition` in Readern, PDF-Seitenzahl, Backup/Sync.
