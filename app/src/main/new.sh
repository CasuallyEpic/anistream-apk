sh
    git add .github/workflows/release.yml
    git commit -m "Add GitHub Release Workflow"
    git push origin main
    sh
    git tag v1.0.0
    git push origin v1.0.0