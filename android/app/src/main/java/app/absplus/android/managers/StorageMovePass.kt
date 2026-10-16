package app.absplus.android.managers

import app.absplus.android.data.LocalLibraryItem

/** One pass of moving app-storage downloads to the selected storage, re-planning before every item. */
object StorageMovePass {
  private const val DISCARDS_BEFORE_RETRY = 2

  fun shouldStart(reconcileNeeded: Boolean, toMoveEmpty: Boolean, recoveryPending: Boolean = false): Boolean =
          reconcileNeeded || !toMoveEmpty || recoveryPending

  fun itemSize(item: LocalLibraryItem): Long = item.localFiles.sumOf { it.size }

  /** Counts per reason; [retry] ids still planned to move are counted as [WaitReason.RETRY]. */
  fun countWaiting(
          plan: MovePlan,
          retry: Set<String> = emptySet()
  ): Map<WaitReason, Int> {
    val counts = linkedMapOf<WaitReason, Int>()
    plan.waiting.values.forEach { counts.merge(it, 1, Int::plus) }
    val retrying = plan.toMove.count { it.id in retry }
    if (retrying > 0) counts[WaitReason.RETRY] = retrying
    return counts
  }

  fun run(
          planFn: () -> MovePlan,
          move: (LocalLibraryItem) -> MoveResult,
          onMoved: (String) -> Unit,
          onStatus: (MoveStatus) -> Unit
  ): MoveStatus {
    val discards = mutableMapOf<String, Int>()
    val retry = mutableSetOf<String>()
    var done = 0
    var bytesDone = 0L
    var lastError: String? = null

    while (true) {
      val plan = planFn()
      val candidates = plan.toMove.filter { it.id !in retry }
      val waiting = countWaiting(plan, retry)

      if (candidates.isEmpty()) {
        val result = MoveStatus(done, done, bytesDone, bytesDone, waiting, running = false, lastError = lastError)
        onStatus(result)
        return result
      }

      val total = done + candidates.size
      val bytesTotal = bytesDone + candidates.sumOf(::itemSize)
      onStatus(MoveStatus(total, done, bytesTotal, bytesDone, waiting, running = true, lastError = lastError))

      val item = candidates.first()
      when (val r = move(item)) {
        is MoveResult.Moved -> {
          done++
          bytesDone += itemSize(item)
          onMoved(item.id)
        }
        is MoveResult.Discarded -> {
          // An item that keeps changing under the copy would otherwise be copied again forever
          val n = discards.merge(item.id, 1, Int::plus)!!
          if (n >= DISCARDS_BEFORE_RETRY) retry += item.id
        }
        is MoveResult.Failed -> {
          // A failure is likely to repeat (disk error, foreign folder): skip the item for this pass, move the others
          retry += item.id
          lastError = r.message
        }
      }
    }
  }

  /**
   * Whole pass: flag on, heal, journal recovery, moves, flag off only if everything above returned normally. Recovery
   * runs first, so an interrupted move is finished or undone before anything new starts; there is no orphan-folder
   * deletion: a folder nothing refers to is never deleted for that reason alone (H03).
   */
  fun reconcile(
          setReconcileNeeded: (Boolean) -> Unit,
          heal: () -> Unit,
          recover: () -> Unit,
          planFn: () -> MovePlan,
          move: (LocalLibraryItem) -> MoveResult,
          onMoved: (String) -> Unit,
          onStatus: (MoveStatus) -> Unit
  ): MoveStatus {
    setReconcileNeeded(true)
    heal()
    recover()
    val status = run(planFn, move, onMoved, onStatus)
    setReconcileNeeded(false)
    return status
  }
}
