<!-- README for GitHub Secrets and Security -->
# 🔐 GitHub Secrets Configuration

## Critical Security Warning ⚠️

**NEVER commit `.env` file to Git!**

The following credentials must be kept secret:
- `TELEGRAM_API_ID` - Your Telegram API ID
- `TELEGRAM_API_HASH` - Your Telegram API Hash
- `TELEGRAM_PHONE_NUMBER` - Your Telegram phone number
- `TELEGRAM_BOT_TOKEN` - Your Telegram Bot Token
- `JAVA_API_KEY` - API key for Java backend

## Using GitHub Secrets for CI/CD

### Step 1: Create GitHub Secrets

Go to your repository → **Settings** → **Secrets and variables** → **Actions**

Click "New repository secret" and add:

| Secret Name | Value | Source |
|---|---|---|
| `TELEGRAM_API_ID` | `38317199` | [my.telegram.org](https://my.telegram.org/apps) |
| `TELEGRAM_API_HASH` | `b6525f30c19d62deaf5b6a5bcbafaf9d` | [my.telegram.org](https://my.telegram.org/apps) |
| `TELEGRAM_PHONE_NUMBER` | `+79046609284` | Your phone number |
| `TELEGRAM_BOT_TOKEN` | `8469296587:AAFpB9-...` | [@BotFather](https://t.me/botfather) |
| `JAVA_API_KEY` | `your-secret-key` | Your choice |

### Step 2: Update Workflows to Use Secrets

In `.github/workflows/ci.yml`:

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up environment
        run: |
          echo "TELEGRAM_API_ID=${{ secrets.TELEGRAM_API_ID }}" >> .env
          echo "TELEGRAM_API_HASH=${{ secrets.TELEGRAM_API_HASH }}" >> .env
          echo "TELEGRAM_PHONE_NUMBER=${{ secrets.TELEGRAM_PHONE_NUMBER }}" >> .env
          echo "TELEGRAM_BOT_TOKEN=${{ secrets.TELEGRAM_BOT_TOKEN }}" >> .env
          echo "JAVA_API_KEY=${{ secrets.JAVA_API_KEY }}" >> .env
          chmod 600 .env
```

### Step 3: Docker Build with Secrets

For Docker builds in GitHub Actions:

```yaml
- name: Build and push Docker image
  uses: docker/build-push-action@v5
  with:
    context: .
    push: true
    secrets: |
      "telegram_api_id=${{ secrets.TELEGRAM_API_ID }}"
      "telegram_api_hash=${{ secrets.TELEGRAM_API_HASH }}"
      "telegram_phone=${{ secrets.TELEGRAM_PHONE_NUMBER }}"
```

## Local Development

### Option 1: Using `.env` file

1. Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```

2. Fill in your credentials:
```bash
# .env
TELEGRAM_API_ID=38317199
TELEGRAM_API_HASH=b6525f30c19d62deaf5b6a5bcbafaf9d
TELEGRAM_PHONE_NUMBER=+79046609284
TELEGRAM_BOT_TOKEN=8469296587:AAFpB9-...
```

3. **IMPORTANT**: Verify `.gitignore` includes `.env`:
```bash
grep -i "\.env" .gitignore  # Should match
```

4. Source the file before running:
```bash
source .env
python export-worker/main.py
```

### Option 2: Using Environment Variables

```bash
export TELEGRAM_API_ID=38317199
export TELEGRAM_API_HASH=b6525f30c19d62deaf5b6a5bcbafaf9d
export TELEGRAM_PHONE_NUMBER=+79046609284
export TELEGRAM_BOT_TOKEN=8469296587:AAFpB9-...

python export-worker/main.py
```

### Option 3: Docker Compose

1. Create `.env` in repository root with your credentials
2. Run Docker Compose:
```bash
docker-compose up -d
```

The `.env` file is automatically loaded by Docker Compose.

## Security Checklist

- [ ] `.env` file is in `.gitignore`
- [ ] `.env` file is never committed to git
- [ ] GitHub Secrets are configured (for CI/CD)
- [ ] Local `.env` file has restricted permissions: `chmod 600 .env`
- [ ] No credentials in code files
- [ ] No credentials in commit messages
- [ ] No credentials in logs

## What If Credentials Are Compromised?

### If a credential is leaked:

1. **Immediately revoke it**:
   - Telegram API ID/Hash: Regenerate at [my.telegram.org](https://my.telegram.org/apps)
   - Bot Token: Regenerate with [@BotFather](https://t.me/botfather)

2. **Update GitHub Secrets**:
   - Go to repository settings
   - Update the exposed secret
   - All future deployments will use the new value

3. **Purge git history** (if accidentally committed):
   ```bash
   # If secret was committed, use git-filter-repo to remove
   pip install git-filter-repo
   git filter-repo --replace-text expressions.txt
   git push --force-with-lease
   ```

4. **Check recent actions**:
   - Review GitHub Actions logs for unauthorized access
   - Check Telegram Bot activity logs

## Environment Variables Reference

### Telegram API
- `TELEGRAM_API_ID` - Get from [my.telegram.org/apps](https://my.telegram.org/apps)
- `TELEGRAM_API_HASH` - Get from [my.telegram.org/apps](https://my.telegram.org/apps)
- `TELEGRAM_PHONE_NUMBER` - Your Telegram account phone
- `TELEGRAM_BOT_TOKEN` - Get from [@BotFather](https://t.me/botfather)

### Redis
- `REDIS_HOST` - Redis server host (default: `redis`)
- `REDIS_PORT` - Redis server port (default: `6379`)
- `REDIS_PASSWORD` - Redis password (optional)

### Java Bot API
- `JAVA_API_BASE_URL` - Java Bot API endpoint
- `JAVA_API_KEY` - Shared secret with Java backend

### Worker
- `WORKER_NAME` - Worker identifier for logging
- `MAX_WORKERS` - Max concurrent workers
- `LOG_LEVEL` - Logging level (DEBUG, INFO, WARNING, ERROR)

## Best Practices

✅ **DO:**
- Use `.env.example` as template (with dummy values)
- Store secrets in GitHub Secrets for CI/CD
- Use environment variables for local development
- Rotate credentials regularly
- Log all secret access

❌ **DON'T:**
- Commit `.env` file to git
- Share credentials via email or chat
- Use same credentials across environments
- Log credentials (even in debug mode)
- Hardcode credentials in code

## References

- [GitHub Secrets Documentation](https://docs.github.com/en/actions/security-guides/using-secrets-in-github-actions)
- [Telegram API Documentation](https://core.telegram.org/api/obtaining_api_id)
- [Security Best Practices](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)

---

**Last Updated**: 2025-06-24
**Status**: ✅ Security Audit Complete
