# Production cloud.lua Protocol Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover the production `cloud.lua` network contract as far as the encrypted artifact permits, and prove that the new backend remains usable by existing clients without updating their scripts.

**Architecture:** Keep the production `script.lr` as an immutable, ignored evidence artifact. Use small Python standard-library tools to fingerprint the archive and probe the `\x86LUA` payload, then reconcile recovered evidence with the historical plaintext client and backend behavior. Lock legacy compatibility into Java contract tests, and add the missing server-side gate that prevents a device awaiting halt acknowledgement from receiving a new task.

**Tech Stack:** Python 3.12 standard library, PowerShell 7, ZIP/Lua artifact analysis, Java 11, Spring Boot 2.7, Gradle 7.4.1, JUnit 5, Mockito, Spring MockMvc.

---

## File map

### Create

- `tools/__init__.py`
- `tools/cloud_lua_reverse/__init__.py`
- `tools/cloud_lua_reverse/artifact.py`
- `tools/cloud_lua_reverse/x86lua.py`
- `tools/cloud_lua_reverse/protocol_reference.py`
- `tools/cloud_lua_reverse/tests/test_artifact.py`
- `tools/cloud_lua_reverse/tests/test_x86lua.py`
- `tools/cloud_lua_reverse/tests/test_protocol_reference.py`
- `analysis/cloud-lua/manifest.json`
- `analysis/cloud-lua/x86lua-report.json`
- `analysis/cloud-lua/protocol-presence.json`
- `analysis/cloud-lua/reverse-notes.md`
- `analysis/cloud-lua/protocol-matrix.md`
- `src/test/java/moe/dazecake/inquisition/controller/LegacyClientHttpContractTest.java`
- `src/test/java/moe/dazecake/inquisition/controller/SanControllerLegacyContractTest.java`

### Modify

- `.gitignore`
- `src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java`
- `src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java`
- `src/test/java/moe/dazecake/inquisition/service/impl/HeartBeatServiceImplTest.java`
- `src/test/java/moe/dazecake/inquisition/service/impl/TaskAssignmentServiceTest.java`
- `src/test/java/moe/dazecake/inquisition/service/impl/AccountRuntimeServiceTest.java`

### Must not modify or publish

- `D:/自建功能/审判庭/_worktrees/arklights-reliability/cloud.lua`
- Any production `script.lr`
- Any customer device script or APK
- Server `129.204.9.242`

### Dirty-worktree rule

This worktree already contains uncommitted reliability changes. Commit newly created tooling and evidence files normally. For Java files that were already dirty before this plan, do not stage or commit the whole file merely to capture one new hunk; verify the focused diff and leave it uncommitted for the final reliability-branch review.

---

### Task 1: Add safe artifact inspection tooling

**Files:**
- Modify: `.gitignore`
- Create: `tools/__init__.py`
- Create: `tools/cloud_lua_reverse/__init__.py`
- Create: `tools/cloud_lua_reverse/artifact.py`
- Test: `tools/cloud_lua_reverse/tests/test_artifact.py`

- [ ] **Step 1: Add the failing artifact tests**

Create `test_artifact.py`:

```python
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.cloud_lua_reverse.artifact import build_manifest, extract_member


class ArtifactTest(unittest.TestCase):
    def test_manifest_contains_hashes_and_sorted_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "script.lr"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("脚本/cloud.lua", b"\x86LUA\x03\x00payload")
                package.writestr("entry.json", json.dumps({"enc": "1"}))
            manifest = build_manifest(archive)
            self.assertEqual(32, len(manifest["artifact"]["md5"]))
            self.assertEqual(64, len(manifest["artifact"]["sha256"]))
            self.assertEqual(["entry.json", "脚本/cloud.lua"], [item["name"] for item in manifest["entries"]])

    def test_extract_member_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "script.lr"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("../escape.lua", b"bad")
            with self.assertRaises(ValueError):
                extract_member(archive, "../escape.lua", root / "out")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests and verify RED**

```powershell
python -m unittest tools.cloud_lua_reverse.tests.test_artifact -v
```

Expected: import failure for `tools.cloud_lua_reverse.artifact`.

- [ ] **Step 3: Implement `artifact.py`**

Create empty package marker files and this module:

```python
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path, PurePosixPath


def digest_file(path: Path) -> dict[str, object]:
    md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            size += len(chunk)
            md5.update(chunk)
            sha256.update(chunk)
    return {"size": size, "md5": md5.hexdigest(), "sha256": sha256.hexdigest()}


def _validate_member(name: str) -> PurePosixPath:
    member = PurePosixPath(name)
    if member.is_absolute() or ".." in member.parts:
        raise ValueError(f"unsafe archive member: {name}")
    return member


def build_manifest(path: Path) -> dict[str, object]:
    with zipfile.ZipFile(path) as package:
        entries = []
        for info in sorted(package.infolist(), key=lambda item: item.filename):
            _validate_member(info.filename)
            entries.append({
                "name": info.filename,
                "size": info.file_size,
                "compressedSize": info.compress_size,
                "crc32": f"{info.CRC:08x}",
                "timestamp": list(info.date_time),
            })
    return {"artifact": digest_file(path), "entries": entries}


def extract_member(path: Path, name: str, output_dir: Path) -> Path:
    member = _validate_member(name)
    target = output_dir.joinpath(*member.parts)
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as package:
        info = package.getinfo(name)
        _validate_member(info.filename)
        with package.open(info) as source, target.open("wb") as destination:
            shutil.copyfileobj(source, destination)
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    manifest = commands.add_parser("manifest")
    manifest.add_argument("archive", type=Path)
    manifest.add_argument("--output", required=True, type=Path)
    extract = commands.add_parser("extract")
    extract.add_argument("archive", type=Path)
    extract.add_argument("member")
    extract.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "manifest":
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(build_manifest(args.archive), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(extract_member(args.archive, args.member, args.output_dir))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Ignore raw artifacts**

Append to `.gitignore`:

```gitignore
/analysis/cloud-lua/original/
/analysis/cloud-lua/extracted/
/analysis/cloud-lua/runtime/
```

- [ ] **Step 5: Verify GREEN and commit**

```powershell
python -m unittest tools.cloud_lua_reverse.tests.test_artifact -v
git add .gitignore tools
git commit -m "tools: add safe lr artifact inspection"
```

Expected: `Ran 2 tests`, `OK`, then one focused commit.

---

### Task 2: Acquire and fingerprint the production package

**Files:**
- Generate ignored: `analysis/cloud-lua/original/script.lr`
- Generate: `analysis/cloud-lua/manifest.json`
- Generate ignored: `analysis/cloud-lua/extracted/脚本/cloud.lua`
- Generate ignored: `analysis/cloud-lua/extracted/entry.json`

- [ ] **Step 1: Query update metadata without changing it**

```powershell
$metadata = Invoke-RestMethod -Uri 'http://ark.aegirtech.com:8080/checkUpdate?lr_md5=null&skill_md5=null' -TimeoutSec 15
if ($metadata.code -ne 200 -or -not $metadata.data.lrUrl -or -not $metadata.data.lrMD5) { throw 'Invalid update metadata response' }
$metadata.data | ConvertTo-Json -Depth 5
```

Expected: a non-empty `lrUrl` and 32-character `lrMD5`.

- [ ] **Step 2: Download to ignored storage and verify MD5**

```powershell
$output = 'analysis/cloud-lua/original/script.lr'
New-Item -ItemType Directory -Force (Split-Path $output) | Out-Null
Invoke-WebRequest -Uri $metadata.data.lrUrl -OutFile $output -TimeoutSec 60
$actual = (Get-FileHash $output -Algorithm MD5).Hash.ToLowerInvariant()
if ($actual -ne $metadata.data.lrMD5.ToLowerInvariant()) { throw "MD5 mismatch: $actual" }
```

- [ ] **Step 3: Generate manifest and extract only required evidence**

```powershell
python -m tools.cloud_lua_reverse.artifact manifest analysis/cloud-lua/original/script.lr --output analysis/cloud-lua/manifest.json
python -m tools.cloud_lua_reverse.artifact extract analysis/cloud-lua/original/script.lr entry.json --output-dir analysis/cloud-lua/extracted
python -m tools.cloud_lua_reverse.artifact extract analysis/cloud-lua/original/script.lr '脚本/cloud.lua' --output-dir analysis/cloud-lua/extracted
```

- [ ] **Step 4: Verify package invariants**

```powershell
$manifest = Get-Content analysis/cloud-lua/manifest.json -Raw | ConvertFrom-Json
if (($manifest.entries | Where-Object name -eq '脚本/cloud.lua').Count -ne 1) { throw 'cloud.lua missing' }
if (($manifest.entries | Where-Object name -like '脚本/*.lua').Count -lt 20) { throw 'unexpected package layout' }
$head = [IO.File]::ReadAllBytes('analysis/cloud-lua/extracted/脚本/cloud.lua')[0..5]
if (($head | ForEach-Object { '{0:X2}' -f $_ }) -join ' ' -ne '86 4C 55 41 03 00') { throw 'unexpected Lua header' }
```

- [ ] **Step 5: Commit only the manifest**

```powershell
git add analysis/cloud-lua/manifest.json
git commit -m "docs: record production lr artifact manifest"
```

---

### Task 3: Probe the custom x86LUA payload

**Files:**
- Create: `tools/cloud_lua_reverse/x86lua.py`
- Test: `tools/cloud_lua_reverse/tests/test_x86lua.py`
- Generate: `analysis/cloud-lua/x86lua-report.json`

- [ ] **Step 1: Add failing bytecode-probe tests**

Create `test_x86lua.py`:

```python
import unittest

from tools.cloud_lua_reverse.x86lua import printable_strings, probe_bytes


class X86LuaTest(unittest.TestCase):
    def test_probe_recognizes_header_and_version(self):
        report = probe_bytes(b"\x86LUA\x03\x00abc123\x00")
        self.assertTrue(report["isX86Lua"])
        self.assertEqual(3, report["version"])
        self.assertEqual("864c55410300", report["headerHex"])

    def test_printable_strings_applies_minimum_length(self):
        self.assertEqual(["hello", "world!"], printable_strings(b"\x00hello\x01abc\x00world!", 5))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
python -m unittest tools.cloud_lua_reverse.tests.test_x86lua -v
```

Expected: import failure for `x86lua`.

- [ ] **Step 3: Implement `x86lua.py`**

```python
from __future__ import annotations

import argparse
import collections
import json
import math
import re
from pathlib import Path


HEADER = b"\x86LUA"


def printable_strings(data: bytes, minimum: int = 4) -> list[str]:
    pattern = re.compile(rb"[\x20-\x7e]{%d,}" % minimum)
    return [match.decode("ascii") for match in pattern.findall(data)]


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = collections.Counter(data)
    total = len(data)
    return -sum((count / total) * math.log2(count / total) for count in counts.values())


def probe_bytes(data: bytes) -> dict[str, object]:
    strings = printable_strings(data)
    return {
        "size": len(data),
        "isX86Lua": data.startswith(HEADER),
        "version": data[4] if len(data) > 4 and data.startswith(HEADER) else None,
        "headerHex": data[:6].hex(),
        "entropy": round(entropy(data), 6),
        "printableStringCount": len(strings),
        "printableStrings": strings[:200],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    files = [args.input] if args.input.is_file() else sorted(args.input.rglob("*.lua"))
    report = {
        str(path.relative_to(args.input) if args.input.is_dir() else path.name): probe_bytes(path.read_bytes())
        for path in files
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Verify GREEN**

```powershell
python -m unittest tools.cloud_lua_reverse.tests.test_x86lua -v
```

Expected: `Ran 2 tests`, `OK`.

- [ ] **Step 5: Extract compiled Lua files and generate the report**

```powershell
Expand-Archive -LiteralPath analysis/cloud-lua/original/script.lr -DestinationPath analysis/cloud-lua/extracted -Force
python -m tools.cloud_lua_reverse.x86lua analysis/cloud-lua/extracted/脚本 --output analysis/cloud-lua/x86lua-report.json
```

Expected: every compiled Lua entry reports `isX86Lua=true`; useful surviving plaintext is listed without interpretation.

- [ ] **Step 6: Commit probe and report**

```powershell
git add tools/cloud_lua_reverse/x86lua.py tools/cloud_lua_reverse/tests/test_x86lua.py analysis/cloud-lua/x86lua-report.json
git commit -m "tools: probe compiled x86lua artifacts"
```

---

### Task 4: Correlate production bytecode with the historical cloud protocol

**Files:**
- Create: `tools/cloud_lua_reverse/protocol_reference.py`
- Test: `tools/cloud_lua_reverse/tests/test_protocol_reference.py`
- Generate: `analysis/cloud-lua/protocol-presence.json`
- Create: `analysis/cloud-lua/reverse-notes.md`

- [ ] **Step 1: Add failing protocol-reference test**

Create `test_protocol_reference.py`:

```python
import unittest

from tools.cloud_lua_reverse.protocol_reference import presence_report


class ProtocolReferenceTest(unittest.TestCase):
    def test_presence_report_marks_visible_and_hidden_tokens(self):
        report = presence_report(b"POST /heartBeat assignmentId")
        self.assertTrue(report["/heartBeat"])
        self.assertTrue(report["assignmentId"])
        self.assertFalse(report["/completeTask"])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test and verify RED**

```powershell
python -m unittest tools.cloud_lua_reverse.tests.test_protocol_reference -v
```

Expected: import failure for `protocol_reference`.

- [ ] **Step 3: Implement the fixed token inventory**

Create `protocol_reference.py`:

```python
from __future__ import annotations

import argparse
import json
from pathlib import Path


TOKENS = (
    "/heartBeat", "/getTask", "/addLog", "/uploadImage",
    "/completeTask", "/failTask", "/sanReport", "/haltComplete",
    "deviceToken", "assignmentId", "clientVersion", "accountId",
    "imageUrl", "taskType",
)


def presence_report(data: bytes) -> dict[str, bool]:
    return {token: token.encode("utf-8") in data for token in TOKENS}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(presence_report(args.input.read_bytes()), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Verify GREEN and compare the production file**

```powershell
python -m unittest tools.cloud_lua_reverse.tests.test_protocol_reference -v
python -m tools.cloud_lua_reverse.protocol_reference analysis/cloud-lua/extracted/脚本/cloud.lua --output analysis/cloud-lua/protocol-presence.json
```

Expected: test passes; the JSON records which historical constants survive visibly.

- [ ] **Step 5: Create the evidence notes**

Create `analysis/cloud-lua/reverse-notes.md` with these exact sections:

```markdown
# cloud.lua Reverse Notes

## Artifact

- Package hashes, timestamps, entry count, cloud.lua size and header.

## Static recovery

- Every recovered endpoint, field, status code, function name, or repeated structure.
- If no token is visible: "The payload encrypts or encodes protocol constants; absence is evidence of opacity, not evidence that an endpoint is unused."

## Decoder triage

- Each inspected runtime/compiler/APK artifact, exact command, hash, and result.
- No source-recovery claim without stable constants or instructions reproduced from the production file.

## Protocol conclusion

- Mark each operation as Confirmed static, Confirmed runtime, Historical reference, or Unresolved.
```

- [ ] **Step 6: Perform decoder triage without modifying artifacts**

```powershell
rg -a -n --hidden --glob '!analysis/cloud-lua/original/**' --glob '!analysis/cloud-lua/extracted/**' 'x86LUA|installLrPkg|restartPackage|httpPost|heartBeat' D:/自建功能/审判庭 D:/自建功能/核云服务器
```

Inspect only files directly referenced by those results. Record negative findings instead of guessing. If no decoder exists locally, continue with protocol compatibility evidence and mark compiled internals `Unresolved`.

- [ ] **Step 7: Apply the runtime-capture gate**

Check only for a non-production local runtime:

```powershell
$candidates = @(
  'D:/自建功能/审判庭/_worktrees/arklights-reliability/localConfig.py',
  'D:/自建功能/审判庭/ArkLights/localConfig.py'
)
$available = $candidates | Where-Object { Test-Path $_ }
$available
```

If no candidate exists, record `Runtime capture unavailable: no isolated local LR project configured` in `reverse-notes.md` and keep affected operations `Unresolved` or `Historical reference`. Do not attach instrumentation to customer devices. If an isolated local project exists, first verify that it uses no production account and points to a local mock backend; otherwise treat it as unavailable.

- [ ] **Step 8: Commit correlation evidence**

```powershell
git add tools/cloud_lua_reverse/protocol_reference.py tools/cloud_lua_reverse/tests/test_protocol_reference.py analysis/cloud-lua/protocol-presence.json analysis/cloud-lua/reverse-notes.md
git commit -m "docs: correlate production cloud protocol evidence"
```

---

### Task 5: Characterize legacy HTTP request binding

**Files:**
- Create: `src/test/java/moe/dazecake/inquisition/controller/LegacyClientHttpContractTest.java`

- [ ] **Step 1: Add legacy HTTP contract tests**

Create `LegacyClientHttpContractTest.java`:

```java
package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.dto.heartbeat.HeartBeatDTO;
import moe.dazecake.inquisition.model.dto.log.AddLogDTO;
import moe.dazecake.inquisition.service.impl.HeartBeatServiceImpl;
import moe.dazecake.inquisition.service.impl.LogServiceImpl;
import moe.dazecake.inquisition.service.impl.TaskServiceImpl;
import moe.dazecake.inquisition.utils.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyClientHttpContractTest {

    @Test
    void legacyHeartbeatBindsMissingMetadataAsNull() throws Exception {
        var service = mock(HeartBeatServiceImpl.class);
        when(service.postHeartBeat(any())).thenReturn(Result.success("success"));
        var controller = new HeartBeatController();
        ReflectionTestUtils.setField(controller, "heartBeatService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/heartBeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1001,\"deviceToken\":\"device-1\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(HeartBeatDTO.class);
        verify(service).postHeartBeat(captor.capture());
        assertNull(captor.getValue().getAssignmentId());
        assertNull(captor.getValue().getClientVersion());
    }

    @Test
    void legacyTaskReportsBindMissingAssignmentIdAsNull() throws Exception {
        var service = mock(TaskServiceImpl.class);
        when(service.completeTask("device-1", null, "image")).thenReturn(Result.success("success"));
        when(service.failTask("device-1", null, "network", "image")).thenReturn(Result.success("success"));
        var controller = new TaskController();
        controller.taskService = service;
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/completeTask").param("deviceToken", "device-1").param("imageUrl", "image"))
                .andExpect(status().isOk());
        mvc.perform(post("/failTask").param("deviceToken", "device-1").param("type", "network").param("imageUrl", "image"))
                .andExpect(status().isOk());

        verify(service).completeTask("device-1", null, "image");
        verify(service).failTask("device-1", null, "network", "image");
    }

    @Test
    void legacyLogPayloadBindsMissingIdentityFieldsAsNull() throws Exception {
        var service = mock(LogServiceImpl.class);
        var controller = new LogController();
        controller.logService = service;
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/addLog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"INFO\",\"title\":\"登录成功\",\"detail\":\"登录成功\",\"from\":\"device-1\",\"account\":\"legacy\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(AddLogDTO.class);
        verify(service).addLog(captor.capture(), eq(false));
        assertNull(captor.getValue().getAssignmentId());
        assertNull(captor.getValue().getAccountId());
    }
}
```

- [ ] **Step 2: Run characterization tests**

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-11.0.30.7-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
./gradlew.bat test --tests 'moe.dazecake.inquisition.controller.LegacyClientHttpContractTest' --no-daemon
```

Expected: PASS. If binding reports a missing optional parameter, change only the task-report signatures to this explicit form and rerun:

```java
public Result<String> completeTask(
        @RequestParam String deviceToken,
        @RequestParam(required = false) String assignmentId,
        @RequestParam(required = false) String imageUrl) {
    return taskService.completeTask(deviceToken, assignmentId, imageUrl);
}

public Result<String> failTask(
        @RequestParam String deviceToken,
        @RequestParam(required = false) String assignmentId,
        @RequestParam String type,
        @RequestParam(required = false) String imageUrl) {
    return taskService.failTask(deviceToken, assignmentId, type, imageUrl);
}
```

- [ ] **Step 3: Commit HTTP contract tests**

```powershell
git add src/test/java/moe/dazecake/inquisition/controller/LegacyClientHttpContractTest.java
git commit -m "test: lock legacy client http contract"
```

---

### Task 6: Prevent halted devices from receiving a new assignment

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java:143-161`
- Modify: `src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java`

- [ ] **Step 1: Add the failing halt-gate test**

Add to `TaskServiceImplTest`:

```java
@Test
void haltedDeviceCannotReceiveAnotherTaskBeforeAcknowledgement() {
    var service = new TaskServiceImpl();
    service.dynamicInfo = new DynamicInfo();
    service.dynamicInfo.getHaltList().add("device-1");

    var result = service.getTask("device-1");

    assertEquals(500, result.getCode());
    assertEquals("设备等待停机确认，暂不分配新任务", result.getMsg());
}
```

- [ ] **Step 2: Run test and verify RED**

```powershell
./gradlew.bat test --tests 'moe.dazecake.inquisition.service.impl.TaskServiceImplTest.haltedDeviceCannotReceiveAnotherTaskBeforeAcknowledgement' --no-daemon
```

Expected: FAIL because `getTask` reaches device lookup instead of returning code `500`.

- [ ] **Step 3: Add the minimal halt gate**

In `TaskServiceImpl.getTask`, after the global active check and before device lookup, add:

```java
synchronized (dynamicInfo.getHaltList()) {
    if (dynamicInfo.getHaltList().contains(deviceToken)) {
        return Result.failed(500, "设备等待停机确认，暂不分配新任务");
    }
}
```

- [ ] **Step 4: Run task-service tests and verify GREEN**

```powershell
./gradlew.bat test --tests 'moe.dazecake.inquisition.service.impl.TaskServiceImplTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Record the halt-gate checkpoint without staging pre-existing changes**

```powershell
git diff --check -- src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java
git diff -- src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java
```

Expected: the halt gate and its test are visible with no whitespace errors. Do not stage these already-dirty files yet.

---

### Task 7: Lock legacy service semantics into tests

**Files:**
- Modify: `src/test/java/moe/dazecake/inquisition/service/impl/TaskAssignmentServiceTest.java`
- Modify: `src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java`
- Modify: `src/test/java/moe/dazecake/inquisition/service/impl/HeartBeatServiceImplTest.java`
- Modify: `src/test/java/moe/dazecake/inquisition/service/impl/AccountRuntimeServiceTest.java`
- Create: `src/test/java/moe/dazecake/inquisition/controller/SanControllerLegacyContractTest.java`

- [ ] **Step 1: Cover null and blank assignment IDs**

Extend `rejectsStaleAssignmentIdsButKeepsLegacyDeviceCompatibility`:

```java
assertTrue(service.matchesSubmission(assignment, "device-1", null));
assertTrue(service.matchesSubmission(assignment, "device-1", ""));
assertTrue(service.matchesSubmission(assignment, "device-1", "   "));
```

- [ ] **Step 2: Add legacy completion coverage**

Add to `TaskServiceImplTest`:

```java
@Test
void legacyCompletionWithoutAssignmentIdClosesCurrentDeviceAssignment() {
    var service = taskCompletionService();
    var assignment = assignment("assignment-current");
    when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
    when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", null)).thenReturn(true);
    when(service.taskAssignmentService.closeAssignment(
            assignment, "COMPLETED", "device reported completion", false)).thenReturn(true);

    var result = service.completeTask("device-1", null, null);

    assertEquals(200, result.getCode());
    verify(service.taskAssignmentService).closeAssignment(
            assignment, "COMPLETED", "device reported completion", false);
}
```

- [ ] **Step 3: Add idempotent legacy halt acknowledgement coverage**

Add to `HeartBeatServiceImplTest`:

```java
@Test
void legacyHaltAcknowledgementIsIdempotent() {
    var service = new HeartBeatServiceImpl();
    service.dynamicInfo = new DynamicInfo();
    service.dynamicInfo.getHaltList().add("device-1");
    service.dynamicInfo.getHaltList().add("device-1");
    var heartbeat = new HeartBeatDTO(1, "device-1", null, null);

    assertEquals(200, service.postHaltComplete(heartbeat).getCode());
    assertEquals(200, service.postHaltComplete(heartbeat).getCode());
    assertTrue(!service.dynamicInfo.getHaltList().contains("device-1"));
}
```

- [ ] **Step 4: Add old sanity-request coverage**

Create `SanControllerLegacyContractTest.java`:

```java
package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SanControllerLegacyContractTest {

    @Test
    void legacySanityReportUsesCurrentDeviceAssignment() {
        var controller = new SanController();
        var dynamicInfo = new DynamicInfo();
        var assignments = mock(TaskAssignmentService.class);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-current")
                .setAccountId(398L)
                .setDeviceToken("device-1");
        when(assignments.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        ReflectionTestUtils.setField(controller, "dynamicInfo", dynamicInfo);
        ReflectionTestUtils.setField(controller, "taskAssignmentService", assignments);

        var result = controller.SanReport(18, 135, "device-1", null);

        assertEquals(200, result.getCode());
        assertEquals(18, dynamicInfo.getUserSanInfoMap().get(398L).getSan());
        assertEquals(135, dynamicInfo.getUserSanInfoMap().get(398L).getMaxSan());
    }
}
```

- [ ] **Step 5: Verify old logs without a current task do not refresh runtime**

Add to `AccountRuntimeServiceTest`:

```java
@Test
void legacyLogWithoutCurrentAssignmentDoesNotRefreshRuntimeAnchor() {
    var service = new AccountRuntimeService();
    service.runtimeMapper = mock(AccountRuntimeMapper.class);
    service.taskAssignmentService = mock(TaskAssignmentService.class);
    service.accountMapper = mock(AccountMapper.class);
    service.logMapper = mock(LogMapper.class);
    service.dynamicInfo = new DynamicInfo();
    service.messageService = mock(MessageServiceImpl.class);
    var log = new LogEntity().setAccountId(398L).setFrom("device-1")
            .setLevel("INFO").setTitle("登录成功").setDetail("登录成功")
            .setTime(LocalDateTime.now());
    when(service.taskAssignmentService.recordProgress(
            "device-1", null, "INFO", "登录成功", "登录成功")).thenReturn(false);

    assertTrue(!service.onLog(log, false));
    verify(service.runtimeMapper, never()).insert(any());
    verify(service.runtimeMapper, never()).updateById(any());
}
```

- [ ] **Step 6: Run all legacy semantic tests**

```powershell
./gradlew.bat test --rerun-tasks `
  --tests 'moe.dazecake.inquisition.controller.LegacyClientHttpContractTest' `
  --tests 'moe.dazecake.inquisition.controller.SanControllerLegacyContractTest' `
  --tests 'moe.dazecake.inquisition.service.impl.TaskAssignmentServiceTest' `
  --tests 'moe.dazecake.inquisition.service.impl.TaskServiceImplTest' `
  --tests 'moe.dazecake.inquisition.service.impl.HeartBeatServiceImplTest' `
  --tests 'moe.dazecake.inquisition.service.impl.AccountRuntimeServiceTest' `
  --no-daemon
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 7: Record the compatibility-test checkpoint without staging pre-existing tests**

```powershell
git diff --check -- src/test/java/moe/dazecake/inquisition/controller/SanControllerLegacyContractTest.java src/test/java/moe/dazecake/inquisition/service/impl/TaskAssignmentServiceTest.java src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java src/test/java/moe/dazecake/inquisition/service/impl/HeartBeatServiceImplTest.java src/test/java/moe/dazecake/inquisition/service/impl/AccountRuntimeServiceTest.java
git status --short -- src/test/java/moe/dazecake/inquisition/controller/SanControllerLegacyContractTest.java src/test/java/moe/dazecake/inquisition/service/impl/TaskAssignmentServiceTest.java src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java src/test/java/moe/dazecake/inquisition/service/impl/HeartBeatServiceImplTest.java src/test/java/moe/dazecake/inquisition/service/impl/AccountRuntimeServiceTest.java
```

Expected: no whitespace errors; new controller tests are untracked and existing reliability tests remain modified but unstaged.

---

### Task 8: Publish the protocol matrix and perform final verification

**Files:**
- Create: `analysis/cloud-lua/protocol-matrix.md`
- Modify: `analysis/cloud-lua/reverse-notes.md`

- [ ] **Step 1: Write the evidence matrix**

Use this header in `protocol-matrix.md`:

```markdown
| Operation | Method/path | Legacy request | Production static evidence | Backend behavior | Compatibility result | Evidence level |
| --- | --- | --- | --- | --- | --- | --- |
```

Include exactly: heartbeat, get task, add log, upload image, complete task, fail task, sanity report, halt complete. Evidence level must be one of `Confirmed static`, `Confirmed runtime`, `Historical reference`, or `Unresolved`.

- [ ] **Step 2: Record residual risk**

Add to `reverse-notes.md`, changing it only if stronger evidence was recovered:

```markdown
An old client does not return an assignment generation identifier. The backend therefore cannot prove task generation in every same-device race. Safety is provided by one active lease per device, hard lease expiry, halt-before-reassignment, and rejection of explicit mismatches. Strict cross-generation proof remains an optional future client enhancement, not a deployment prerequisite.
```

- [ ] **Step 3: Run all Python tool tests**

```powershell
python -m unittest discover -s tools/cloud_lua_reverse/tests -p 'test_*.py' -v
```

Expected: all reverse-tool tests pass.

- [ ] **Step 4: Run Java compatibility tests with execution forced**

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-11.0.30.7-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
./gradlew.bat test --rerun-tasks `
  --tests 'moe.dazecake.inquisition.controller.LegacyClientHttpContractTest' `
  --tests 'moe.dazecake.inquisition.controller.SanControllerLegacyContractTest' `
  --tests 'moe.dazecake.inquisition.service.impl.TaskAssignmentServiceTest' `
  --tests 'moe.dazecake.inquisition.service.impl.TaskServiceImplTest' `
  --tests 'moe.dazecake.inquisition.service.impl.HeartBeatServiceImplTest' `
  --tests 'moe.dazecake.inquisition.service.impl.AccountRuntimeServiceTest' `
  --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Build backend artifact**

```powershell
./gradlew.bat bootJar --rerun-tasks --no-daemon
```

Expected: `BUILD SUCCESSFUL` and a JAR under `build/libs/`.

- [ ] **Step 6: Verify scope and formatting**

```powershell
git diff --check
git status --short
git -C D:/自建功能/审判庭/_worktrees/arklights-reliability diff -- cloud.lua
```

Expected:

- `git diff --check` reports no errors.
- Raw packages, extracted bytecode, and runtime captures are ignored.
- The ArkLights `cloud.lua` diff remains outside backend commits and deployments.

- [ ] **Step 7: Commit final evidence report**

```powershell
git add analysis/cloud-lua/protocol-matrix.md analysis/cloud-lua/reverse-notes.md
git commit -m "docs: document cloud protocol compatibility evidence"
```

- [ ] **Step 8: Stop before deployment**

Do not run SSH upload, Docker replacement, database migration, update-package upload, or customer-device distribution. Present hashes, recovered protocol content, test results, fixed backend gaps, and unresolved reverse limits for explicit deployment approval.
