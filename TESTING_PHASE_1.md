# PHASE 1: Stabilization Testing Guide

**Goal:** Test bot stability with ONLY YOU using it. Find and fix bugs before expanding to other users.

**Server Resources:** 1 core, 3-4GB RAM (weak server - be careful!)

---

## Pre-Testing Checklist

- [ ] All code changes deployed to server
- [ ] Environment variables set correctly
- [ ] Redis persistence enabled (`appendonly yes`)
- [ ] Docker containers running:
  ```bash
  docker-compose ps
  # Should show: redis (healthy), export-worker (running)
  ```

---

## Testing Plan

### Test 1: Small Chat (100 messages) - 1-2 minutes

```
1. Select a small Telegram chat (group or private chat)
   - Small enough: ~100 messages

2. Send export request to bot
   Example: "/export 12345" or use inline keyboard

3. Watch logs in real-time:
   docker-compose logs -f export-worker

4. Expected output:
   ✅ Job received and queued
   ✅ Export progress logged (every 500 messages)
   ✅ JSON conversion completed
   ✅ Response sent to Java Bot
   ✅ Cleanup completed (temp files deleted)

5. Time: Should complete in 1-2 minutes

6. Verification:
   - [ ] No errors in logs
   - [ ] Correct JSON file in response
   - [ ] No temp files left on disk
   - [ ] Memory usage < 300MB
```

### Test 2: Medium Chat (10K messages) - 5-10 minutes

```
1. Select a medium Telegram chat
   - Medium size: ~10,000 messages

2. Send export request

3. Monitor logs AND resource usage:
   # In another terminal:
   docker stats telegram-export-worker

4. Watch for:
   - [ ] Memory growth (should stay < 400MB)
   - [ ] CPU usage (should be <= 80%)
   - [ ] No crashes or timeouts
   - [ ] Progress logged every 500 messages

5. Time: Should complete in 5-10 minutes

6. Verify:
   - [ ] JSON is complete and valid
   - [ ] All 10K messages included
   - [ ] Temp files cleaned up
   - [ ] Memory returned to baseline after export
```

### Test 3: Large Chat (100K messages) - ~30 minutes

```
1. Select a large Telegram chat
   - Large size: ~100,000 messages
   OR create artificial test with bulk messages

2. Send export request

3. Monitor continuously:
   docker-compose logs -f export-worker
   docker stats telegram-export-worker

4. This is the stress test! Watch for:
   - [ ] Memory stays < 500MB (CRITICAL!)
   - [ ] CPU stays stable
   - [ ] JOB_TIMEOUT (1800s = 30min) respected
   - [ ] No OOM (Out of Memory) kills
   - [ ] Progress logged regularly

5. Time: Should complete in ~30 minutes or hit timeout

6. Verify:
   - [ ] JSON response (complete or partial)
   - [ ] No server crashes
   - [ ] Temp files cleaned up
   - [ ] Memory returned to normal
```

---

## What to Check in Logs

### ✅ Good Signs

```
[JOB_START] Memory: 15% used, 2800MB free, CPU 5%
[EXPORT] Exported 500 messages...
[EXPORT] Exported 1000 messages...
[CLEANUP] Cleaned up temp files for task xyz
[JOB_DONE] Memory: 25% used, 2200MB free, CPU 2%
✅ Job completed (100000 messages)
```

### ❌ Bad Signs

```
[ERROR] OOM killer triggered! (Server crashed)
[TIMEOUT] Job exceeded JOB_TIMEOUT (1800 seconds)
[ERROR] Memory: 95% used (Server near crash)
[FATAL] Connection lost to Redis
[ERROR] Cleanup failed: disk full
```

---

## Debug Commands

### Check Docker container status
```bash
docker-compose ps
docker-compose logs export-worker | tail -50
```

### Monitor resources in real-time
```bash
docker stats telegram-export-worker --no-stream
```

### Check disk usage
```bash
df -h
du -sh /home/user/Telegram-Export-Cleaner/*
```

### Check Redis queue status
```bash
docker-compose exec redis redis-cli
> DBSIZE
> KEYS *
> XLEN export-tasks
> XLEN export-results
```

### Restart worker if needed
```bash
docker-compose restart export-worker
```

---

## Success Criteria

✅ **Phase 1 is COMPLETE when:**

1. **Small chat export:** Works perfectly
2. **Medium chat export:** Works with stable memory
3. **Large chat export:** Completes without OOM or timeout
4. **Cleanup:** Temp files are deleted after each job
5. **Monitoring:** Memory logs show healthy behavior
6. **No crashes:** Server stays stable through all tests

---

## Common Issues & Solutions

### Issue: "Export worker not receiving jobs"

```bash
# Check if worker is connected to Redis
docker-compose logs export-worker | grep -i redis

# Restart worker
docker-compose restart export-worker

# Check Redis connectivity
docker-compose exec redis redis-cli ping
```

### Issue: "Memory keeps growing"

```bash
# Check if cleanup is running
docker-compose logs export-worker | grep -i cleanup

# Check temp directory
ls -la /tmp/export_*
rm -rf /tmp/export_*

# If cleanup not working, check psutil import
docker-compose logs export-worker | grep -i psutil
```

### Issue: "Job times out after 30 minutes"

```
This is EXPECTED behavior! 30 minutes is the max.
For chats larger than 100K messages:
- Job times out
- Partial results sent
- Manual export needed or split by date
```

### Issue: "Disk fills up"

```bash
# Check what's using disk space
du -sh /tmp/*
du -sh ./export-worker/*

# Clean up manually
rm -rf /tmp/export_*
docker-compose restart export-worker
```

---

## Next Steps After Phase 1

- ✅ Phase 1 Passed? → Move to Phase 2 (small group testing)
- ❌ Issues Found? → Fix and re-test Phase 1
- ⚠️  Memory Issues? → Implement streaming export (Priority 2)

---

## Monitoring Checklist

Keep a log of each test:

```markdown
## Test Log

### Test 1: Small Chat (Group: "Friends")
- Start time: 2024-XX-XX 10:00
- Duration: 1m 30s
- Messages: 150
- Memory peak: 200MB
- Status: ✅ PASS

### Test 2: Medium Chat (Channel: "News")
- Start time: 2024-XX-XX 11:00
- Duration: 7m 45s
- Messages: 10,000
- Memory peak: 350MB
- Status: ✅ PASS

### Test 3: Large Chat (Group: "General")
- Start time: 2024-XX-XX 12:00
- Duration: 29m 58s
- Messages: 100,000
- Memory peak: 480MB
- Status: ✅ PASS (within limits)

Overall: ✅ PHASE 1 COMPLETE - Ready for Phase 2
```

Good luck! 🚀
