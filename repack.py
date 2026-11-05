import zipfile, os, glob, shutil
root = "/home/hatch/workspace/campus-buddy"
out = root + "/CampusBuddy-完整技术版.zip"
base = "CampusBuddy-完整技术版"
shutil.copy(root + "/backend/target/campus-buddy-1.0.0.jar", root + "/windows-package/campus-buddy-1.0.0.jar")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    def add(src, arc):
        z.write(src, arc)
    add(root + "/CampusBuddy-软件开发全过程文档.md", f"{base}/软件开发全过程文档.md")
    add(root + "/MOBILE.md", f"{base}/移动端运行说明.md")
    add(root + "/README-完整版.md", f"{base}/README.md")
    add(root + "/BUILD-NOTES.md", f"{base}/BUILD-NOTES.md")
    add(root + "/CampusBuddy-第一次汇报-含截图版.pptx", f"{base}/汇报/CampusBuddy-第一次汇报-含截图版.pptx")
    add(root + "/CampusBuddy-网页展示-含截图版.html", f"{base}/汇报/CampusBuddy-网页展示-含截图版.html")
    add(root + "/CampusBuddy-第一次汇报-浅色版.pptx", f"{base}/汇报/CampusBuddy-第一次汇报-浅色版.pptx")
    add(root + "/CampusBuddy-网页展示.html", f"{base}/汇报/CampusBuddy-网页展示.html")
    add(root + "/build_ppt_v4.py", f"{base}/汇报/build_ppt_v4.py")
    for f in sorted(glob.glob(root + "/assets/*.jpg")) + sorted(glob.glob(root + "/assets/erd-postgresql.png")) + sorted(glob.glob(root + "/assets/erd-postgresql.dot")):
        add(f, f"{base}/汇报/assets/" + os.path.basename(f))
    for f in sorted(glob.glob(root + "/screenshots/*.png")):
        add(f, f"{base}/汇报/screenshots/" + os.path.basename(f))
    for f in ["campus-buddy-1.0.0.jar", "run.bat", "README-Windows.md"]:
        add(root + "/windows-package/" + f, f"{base}/运行包/" + f)
    for dirpath, _, files in os.walk(root + "/backend"):
        if "target" in dirpath:
            continue
        for fn in files:
            src = os.path.join(dirpath, fn)
            rel = os.path.relpath(src, root + "/backend")
            if rel.startswith("src/main/resources/static/"):
                continue
            add(src, f"{base}/源码/backend/" + rel)
    for dirpath, _, files in os.walk(root + "/frontend"):
        if "node_modules" in dirpath or "/dist" in dirpath or "/build" in dirpath or ".gradle" in dirpath:
            continue
        for fn in files:
            src = os.path.join(dirpath, fn)
            rel = os.path.relpath(src, root + "/frontend")
            add(src, f"{base}/源码/frontend/" + rel)
    add(root + "/源码/database/schema.sql", f"{base}/源码/database/schema.sql")
    for f in ["test_api.py", "test_api.log", "test_full_flow.py", "test_full_flow.log", "test_audit.py", "test_audit.log", "test_mobile.py"]:
        p = root + "/tests/" + f
        if os.path.exists(p):
            add(p, f"{base}/源码/tests/" + f)
print(out, os.path.getsize(out))
with zipfile.ZipFile(out) as z:
    print(len(z.namelist()), "entries, duplicates:", len(z.namelist()) - len(set(z.namelist())))
