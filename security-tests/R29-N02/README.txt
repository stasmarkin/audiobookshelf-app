R29-N02: download filename collisions (lossy names, completed-file replacement, backup loss)

Both .kt files here are BASELINE REPRODUCTIONS. They assert the DEFECT on trusted plus 6649f76e and therefore fail
once the correction is applied. They are kept here as the permanent record, outside the compiled test source set:

  DownloadCollisionReproductionTest.kt   Codex (prepared 2026-10-15, unchanged, sha256 65a65afd15bda680...).
                                         Sequential collision: "Season1/episode.mp3" and "Season1_episode.mp3" flatten
                                         to one name; episode B's bytes replace episode A's only copy, both records
                                         alias one LocalFile, the backup is deleted. Also documents the lossy transform.
  R29N02BaselineReproductionTest.kt      Claude. Same item and filename from a second server; a crash between the two
                                         finalization renames leaves the only copy in the backup, which the next
                                         finalization deletes; retention of an aliased auto episode deletes a manual
                                         episode's bytes.

Run against plus 6649f76e (Robolectric, network-less container): 5/5 pass, so the defect is reproduced.
Run against the correction: the 4 defect assertions fail (the colliding finalization is held instead), as intended.
To rerun, copy a file into android/app/src/test/java/app/absplus/android/managers/ on the tree under test.

Permanent regression tests: android/app/src/test/java/app/absplus/android/managers/DownloadCollisionSafetyTest.kt
Correction: managers/DownloadDestinationGuard.kt (ownership from existing records and the queue), queue admission
(owner-specific alternative name), internal and device-folder finalization (ownership checked under the lock, unknown
files set aside, interrupted backups restored), starter (same item from another server refused), retention (shared
files kept). Existing downloads keep their names; nothing is migrated.
