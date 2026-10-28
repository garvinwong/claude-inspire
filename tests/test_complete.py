"""斜杠命令与 @ 文件补全测试：命令来源合并、插件只认已启用的、文件检索排序与目录限制。"""
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'hub'))
import complete  # noqa: E402


def _skill(base: Path, name: str, desc: str, fm_name=None):
    d = base / name
    d.mkdir(parents=True)
    head = f'name: {fm_name}\n' if fm_name else ''
    (d / 'SKILL.md').write_text(f'---\n{head}description: {desc}\n---\n\n# 正文\n', encoding='utf-8')


def test_commands_merge_sources(tmp_path):
    proj, cfg = tmp_path / 'proj', tmp_path / 'cfg'
    _skill(proj / '.claude' / 'skills', 'code-animation', '码上动画 —— ' + '很长的说明' * 50)
    (proj / '.claude' / 'commands' / 'ops').mkdir(parents=True)
    (proj / '.claude' / 'commands' / 'ops' / 'deploy.md').write_text('---\ndescription: "上线检查"\n---\n正文', encoding='utf-8')
    (cfg / 'commands').mkdir(parents=True)
    (cfg / 'commands' / 'clear.md').write_text('# 想覆盖内置 clear\n', encoding='utf-8')
    _skill(cfg / 'skills', 'mine', '我的技能', fm_name='my-skill')
    on, off = tmp_path / 'plug-on', tmp_path / 'plug-off'
    _skill(on / 'skills', 'docx', 'Word 文档')
    (on / 'commands').mkdir()
    (on / 'commands' / 'revise.md').write_text('---\ndescription: 修订\n---\n', encoding='utf-8')
    _skill(off / 'skills', 'hidden', '没启用')
    (cfg / 'settings.json').write_text(json.dumps({'enabledPlugins': {'doc@m': True, 'off@m': False}}), encoding='utf-8')
    (cfg / 'plugins').mkdir()
    (cfg / 'plugins' / 'installed_plugins.json').write_text(json.dumps({'plugins': {
        'doc@m': [{'scope': 'user', 'installPath': str(on)}], 'off@m': [{'scope': 'user', 'installPath': str(off)}]}}))

    rows = complete.commands(str(proj), str(cfg))
    by = {r['name']: r for r in rows}
    assert by['/clear']['src'] == '内置' and by['/model']['menu'] is True     # 内置优先，同名自定义不覆盖
    assert sum(1 for r in rows if r['name'] == '/clear') == 1
    assert by['/code-animation']['src'] == '项目技能' and len(by['/code-animation']['desc']) <= complete.DESC_MAX
    assert by['/ops:deploy']['desc'] == '上线检查'
    assert by['/my-skill']['src'] == '我的技能'                                  # frontmatter 的 name 优先于目录名
    assert by['/doc:docx']['src'] == '插件 doc' and '/doc:revise' in by
    assert '/off:hidden' not in by                                              # 没启用的插件不列
    assert '/cost' not in by                                                    # v2.1.310 已并入 /usage


def test_commands_tolerate_missing_dirs(tmp_path):
    rows = complete.commands(str(tmp_path / 'nope'), str(tmp_path / 'nocfg'))
    assert rows and all(r['src'] == '内置' for r in rows)


def test_file_search_ranking_and_git(tmp_path):
    root = tmp_path / 'repo'
    for p in ['src/app.py', 'src/deep/app_utils.py', 'docs/myapp.md', 'README.md', 'ignored.log', 'node_modules/x/app.js']:
        f = root / p
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text('x')
    (root / '.gitignore').write_text('*.log\nnode_modules/\n')
    subprocess.run(['git', 'init', '-q', str(root)], check=True)
    idx = complete.FileIndex()
    got = idx.search(str(root), 'app')
    assert got[:2] == ['src/app.py', 'src/deep/app_utils.py']                  # 文件名开头命中在前
    assert 'docs/myapp.md' in got and 'ignored.log' not in idx.files(str(root))
    assert not any(p.startswith('node_modules') for p in idx.files(str(root)))
    assert idx.search(str(root), 'zzz') == []
    assert idx.search(str(tmp_path / 'missing'), 'a') == []


def test_file_search_without_git_skips_heavy_dirs(tmp_path):
    for p in ['a/b.txt', 'node_modules/m/b.txt', '.cache/b.txt', 'b.txt']:
        f = tmp_path / p
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text('x')
    got = complete.FileIndex().search(str(tmp_path), 'b')
    assert got == ['b.txt', 'a/b.txt']
