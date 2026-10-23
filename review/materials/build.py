# Builds page.html (the "Материалы блоков" review page) from page-template.html and the data next to it:
# data.json (the classifier's verdicts), claude.json (Claude's review), names.json, icons.json.
# fixes/ holds a copy of the user's marks from the page's database.
import os

here = os.path.dirname(os.path.abspath(__file__))
out = open(os.path.join(here, "page-template.html")).read()
for key, name in {"__ICONS__": "icons.json", "__CLAUDE__": "claude.json", "__NAMES__": "names.json", "__DATA__": "data.json"}.items():
    data = open(os.path.join(here, name)).read()
    assert "</script" not in data
    out = out.replace(key, data, 1)
open(os.path.join(here, "page.html"), "w").write(out)
