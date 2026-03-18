# Security Policy

## Credential Management

### ⚠️ Critical: Never Commit Credentials

The following must NEVER be committed to Git:

```
❌ DO NOT COMMIT:
.env
.env.local
.env.*.local
session/
*.key
*.pem
credentials.json
```

✅ These are protected by `.gitignore`

### Environment Variable Hierarchy

```
1. GitHub Secrets (CI/CD)
   └─ Used by: GitHub Actions workflows

2. .env file (Local Development)
   └─ Protected by: .gitignore
   └─ File permissions: chmod 600 .env

3. System Environment Variables
   └─ Used by: Container/server deployments
   └─ Use: export VAR=value

4. .env.example (NO SECRETS)
   └─ Public: Contains only template structure
   └─ Safe to commit
```

### Variables by Sensitivity Level

#### CRITICAL (Change Immediately if Exposed)
- `TELEGRAM_API_HASH` - Allows impersonation
- `TELEGRAM_BOT_TOKEN` - Full bot control
- `JAVA_API_KEY` - Backend API access

#### HIGH (Change if Exposed)
- `TELEGRAM_API_ID` - Identifies API calls
- `TELEGRAM_PHONE_NUMBER` - Account identification

#### MEDIUM
- `REDIS_PASSWORD` - Queue access
- `JAVA_API_BASE_URL` - Infrastructure disclosure

#### LOW
- `REDIS_HOST` - Could be localhost
- `LOG_LEVEL` - Only affects logging verbosity

## Security Checklist

### 🔍 Before Committing Code

```bash
# Check for accidentally committed secrets
git diff --cached | grep -i "api_\|token\|password\|secret"

# Scan .env files
git status | grep "\.env"

# Verify .gitignore protection
grep -E "\.env|\.key|credentials" .gitignore
```

### 🚀 Before Deploying to Production

```bash
# 1. Verify all secrets are in GitHub Secrets
grep -o "secrets\.[A-Z_]*" .github/workflows/*.yml | sort -u

# 2. Verify no hardcoded secrets
grep -r "api_id\|api_hash\|bot_token" export-worker --include="*.py" | grep -v ".env.example"

# 3. Verify environment variable sourcing
grep -r "os.getenv\|os.environ" export-worker --include="*.py"

# 4. Test environment setup
source .env.example  # Should not require secrets
```

### 📝 In Code Review

Checklist for reviewers:

- [ ] No credentials in code files
- [ ] All secrets use environment variables
- [ ] No credentials in log statements
- [ ] No credentials in error messages
- [ ] `.gitignore` protects sensitive files
- [ ] No hardcoded URLs/IPs with credentials
- [ ] Session files are not committed
- [ ] Database credentials are external

## Incident Response

### If You Accidentally Exposed a Credential

**DO THIS IMMEDIATELY:**

1. **Stop using that credential**
   ```bash
   # Don't commit the exposed .env!
   git reset .env
   ```

2. **Regenerate the credential**
   - Telegram API: [my.telegram.org](https://my.telegram.org/apps)
   - Bot Token: [@BotFather](https://t.me/botfather)

3. **Update GitHub Secret**
   - Go to Settings → Secrets and variables → Actions
   - Update the exposed secret
   - New deployments will use new value

4. **Inform team**
   - Comment on PR explaining incident
   - Document in #security channel (if applicable)

5. **Review git history**
   ```bash
   # Check if secret was actually committed
   git log --all -p -- .env | grep -i "api_\|token"
   ```

6. **If in git history, use git-filter-repo**
   ```bash
   # Install tool
   pip install git-filter-repo

   # Create file with replacements
   cat > expressions.txt << 'EOF'
   regex: b6525f30c19d62deaf5b6a5bcbafaf9d
   replace: REDACTED
   EOF

   # Filter history
   git filter-repo --replace-text expressions.txt

   # Force push (use with caution!)
   git push origin --force-with-lease --all
   ```

## GitHub Secrets Setup

### Required Secrets for CI/CD

In **Settings → Secrets and variables → Actions**, create:

| Name | Type | Source | Risk |
|------|------|--------|------|
| `TELEGRAM_API_ID` | Secret | [my.telegram.org](https://my.telegram.org/apps) | 🔴 CRITICAL |
| `TELEGRAM_API_HASH` | Secret | [my.telegram.org](https://my.telegram.org/apps) | 🔴 CRITICAL |
| `TELEGRAM_PHONE_NUMBER` | Secret | Your account | 🟡 HIGH |
| `TELEGRAM_BOT_TOKEN` | Secret | [@BotFather](https://t.me/botfather) | 🔴 CRITICAL |
| `JAVA_API_KEY` | Secret | Your choice | 🟡 HIGH |

### Secret Rotation

Rotate secrets every 90 days:

1. Generate new secret
2. Update GitHub Secret
3. Deploy new version
4. Verify working
5. Revoke old secret
6. Document in changelog

## Container Security

### Docker Build Secrets

Use BuildKit to prevent secrets in image layers:

```dockerfile
# ✅ GOOD: Use build secrets
RUN --mount=type=secret,id=api_key \
    cat /run/secrets/api_key

# ❌ BAD: Secrets in RUN
RUN export API_KEY=secret123
```

### Docker Compose

Keep secrets in `.env`:

```yaml
export-worker:
  environment:
    - TELEGRAM_API_ID=${TELEGRAM_API_ID}
    - TELEGRAM_API_HASH=${TELEGRAM_API_HASH}
```

Never commit `.env` to git.

## Logging Security

### What NOT to Log

```python
❌ DON'T:
logger.info(f"API Key: {api_key}")
logger.debug(f"Token: {token}")
print(f"Password: {password}")

✅ DO:
logger.info("Authentication successful")
logger.error(f"Auth failed for user {user_id}")
logger.debug("Starting export process")
```

### Redact Sensitive Data

```python
# Good: Hide sensitive parts
def redact_token(token: str) -> str:
    if len(token) > 8:
        return token[:4] + "*" * (len(token) - 8) + token[-4:]
    return "****"

logger.info(f"Using token: {redact_token(api_key)}")
```

## Testing

### Don't Test with Real Credentials

Use mocks and fixtures:

```python
# ✅ GOOD: Use mocks
@patch('pyrogram.Client')
async def test_export(mock_client):
    mock_client.get_messages.return_value = [...]

# ❌ BAD: Use real credentials
async def test_export():
    client = Client(api_id=REAL_API_ID, api_hash=REAL_HASH)
```

## Access Control

### Who Should Have Access?

| Secret | Dev | CI/CD | Staging | Prod |
|--------|-----|-------|---------|------|
| API Keys | Yes | Yes | Yes | Yes |
| Passwords | No | No | Yes | Yes |
| Tokens | Dev only | Yes | Yes | Yes |

### GitHub Secret Permissions

- ✅ Allow all Actions
- ❌ Restrict to main branch only
- ✅ Use environment secrets for production
- ❌ Don't share across repositories

## Code Scanning

### Enable GitHub Advanced Security

1. Go to **Settings → Code security and analysis**
2. Enable **Dependabot alerts**
3. Enable **Dependabot security updates**
4. Enable **Code scanning** (optional, paid)

### Local Scanning

```bash
# Scan for secrets
pip install detect-secrets
detect-secrets scan --baseline .secrets.baseline

# Update baseline after review
detect-secrets audit .secrets.baseline
```

## Compliance

### GDPR Compliance
- ✅ No personal data stored
- ✅ Encrypted in transit
- ✅ Session files not backed up

### SOC 2 Compliance
- ✅ Audit logging
- ✅ Access controls
- ✅ Credential rotation
- ✅ Incident response

## References

- [OWASP Secrets Management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
- [GitHub Security Best Practices](https://docs.github.com/en/code-security)
- [Telegram API Security](https://core.telegram.org/api#getting-access)
- [Container Security](https://cheatsheetseries.owasp.org/cheatsheets/Docker_Security_Cheat_Sheet.html)

## Questions?

If you find a security issue:
1. ⚠️ **DO NOT** open a public GitHub issue
2. ✅ Contact security team directly
3. ✅ Provide detailed reproduction steps
4. ✅ Give us time to respond (48 hours)

---

**Last Updated**: 2025-06-24
**Status**: ✅ Security Review Complete
