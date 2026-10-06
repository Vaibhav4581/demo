# Emergency Mesh Web Portal (Vercel Deployment)

This directory contains the standalone landing website and APK distribution portal configured for **1-click deployment on [Vercel](https://vercel.com/)**.

---

## ⚡ How It Works on Vercel

1. **Edge Rewrites:** `vercel.json` automatically routes `/download` and `/app-debug.apk` directly to your GitHub repository's latest release:
   ```
   https://your-vercel-site.vercel.app/download -> https://github.com/Vaibhav4581/demo/releases/latest/download/app-debug.apk
   ```
2. **Auto-Reflects Git Updates:** Whenever you push code to GitHub:
   - Your GitHub Actions workflow automatically builds the APK and publishes it to the `latest` GitHub Release.
   - The Vercel website's QR code and download buttons **automatically stream the newly built APK** without needing you to redeploy on Vercel!
3. **Live Commit Tag:** The page asynchronously fetches the latest commit hash and build date from the GitHub API.

---

## 🚀 How to Deploy to Vercel

### Method 1: Using the Vercel CLI (Fastest — 30 Seconds)

1. Open your terminal in this directory:
   ```bash
   cd emergency-mesh-web
   ```
2. Run Vercel deploy:
   ```bash
   npx vercel
   ```
3. Follow the prompts:
   - *Set up and deploy?* Type `y` and press Enter.
   - *Which scope?* Select your Vercel account.
   - *Link to existing project?* Type `n`.
   - *What's your project's name?* Press Enter (defaults to `emergency-mesh-web`).
   - *In which directory is your code located?* Press Enter (`./`).
   - *Want to modify settings?* Type `n`.
4. Deploy to production:
   ```bash
   npx vercel --prod
   ```
Your site will be live instantly with a free `.vercel.app` domain!

---

### Method 2: Via Vercel Web Dashboard (Import GitHub)

1. Push your repository to GitHub.
2. Go to [https://vercel.com/new](https://vercel.com/new).
3. Import your GitHub repository (`Vaibhav4581/demo`).
4. In the configuration screen:
   - Set **Root Directory** to `emergency-mesh-web`.
   - Leave Build and Output settings as default (no build command needed).
5. Click **Deploy**.

---

### Method 3: Drag & Drop (Vercel Dashboard)

1. Go to [https://vercel.com/](https://vercel.com/) and open your dashboard.
2. Drag and drop the `emergency-mesh-web` folder into the dashboard.
3. Vercel deploys it immediately!
