import { lstatSync, realpathSync, statSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { isAbsolute, join, parse, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const workspace = realpathSync(resolve(fileURLToPath(new URL("..", import.meta.url))));
const names = ["taskFiles", "staging", "backupVault", "restoreQuarantine"];
const values = process.argv.slice(2);
if (values.length !== names.length) {
  throw new Error("Expected task-files, staging, backup-vault and restore-quarantine absolute paths");
}
const within = (parent, child) => {
  const part = relative(parent, child);
  return part === "" || (part !== ".." && !part.startsWith(`..${sep}`) && !isAbsolute(part));
};
const rejectLinkedComponents = (path, name) => {
  let segment = parse(path).root;
  for (const item of path.slice(segment.length).split(sep).filter(Boolean)) {
    segment = join(segment, item);
    if (lstatSync(segment).isSymbolicLink()) throw new Error(`${name}: linked path is forbidden`);
  }
};
const paths = Object.fromEntries(names.map((name, index) => {
  const input = values[index];
  if (!isAbsolute(input)) throw new Error(`${name}: absolute path required`);
  rejectLinkedComponents(resolve(input), name);
  const path = realpathSync(input);
  if (!statSync(path).isDirectory() || within(workspace, path)) {
    throw new Error(`${name}: directory must be outside the workspace`);
  }
  rejectLinkedComponents(path, name);
  return [name, path];
}));
for (const [index, name] of names.entries()) {
  for (const other of names.slice(index + 1)) {
    if (within(paths[name], paths[other]) || within(paths[other], paths[name])) {
      throw new Error(`${name}/${other}: roots must not overlap`);
    }
  }
}
const volume = (path) => parse(path).root.toLowerCase();
const sameBackupVolume = volume(paths.backupVault) === volume(paths.taskFiles);
const sameRestoreVolume = volume(paths.restoreQuarantine) === volume(paths.taskFiles);
const diskNumbers = (() => {
  if (process.platform !== "win32") return null;
  try {
    const output = execFileSync("powershell", ["-NoProfile", "-Command",
      "Get-Partition | Where-Object { $_.DriveLetter } | Select-Object DriveLetter,DiskNumber | ConvertTo-Json -Compress"],
    { encoding: "utf8", timeout: 10000 });
    const partitions = JSON.parse(output);
    return Object.fromEntries([].concat(partitions).map(({ DriveLetter, DiskNumber }) =>
      [String(DriveLetter).toLowerCase(), DiskNumber]));
  } catch {
    return null;
  }
})();
const disk = (path) => diskNumbers?.[parse(path).root[0]?.toLowerCase()] ?? null;
const fileDisk = disk(paths.taskFiles);
const backupDisk = disk(paths.backupVault);
const restoreDisk = disk(paths.restoreQuarantine);
const diskTopologyVerified = fileDisk !== null && backupDisk !== null && restoreDisk !== null;
const backupDifferentDiskNumber = diskTopologyVerified && fileDisk !== backupDisk;
const restoreDifferentDiskNumber = diskTopologyVerified && fileDisk !== restoreDisk;
console.log(JSON.stringify({ profile: "DEVELOPMENT_SAME_DISK_BACKUP_CANDIDATE", paths,
  nonOverlapping: true, linked: false,
  backupIndependentVolume: !sameBackupVolume, restoreIndependentVolume: !sameRestoreVolume,
  diskTopologyVerified, diskNumbers: { taskFiles: fileDisk, backupVault: backupDisk,
    restoreQuarantine: restoreDisk }, backupDifferentDiskNumber, restoreDifferentDiskNumber,
  sameDiskDevBackupPermitted: true, diskFailureResilient: false,
  readyForPhysicalD: false,
  remaining: ["service/job ACL", "backup encryption and rotation", "DB/file snapshot watermarks",
    "expiry/withdrawal high-water mark", "isolated restore and PH-05..07"].concat(
    !diskTopologyVerified ? ["physical disk topology unverified"] : [],
    !backupDifferentDiskNumber ? ["same-disk backup cannot survive task-file disk failure"] : [],
    !restoreDifferentDiskNumber ? ["same-disk restore target cannot survive task-file disk failure"] : [],
    "different disk numbers alone do not prove independent failure domains") }, null, 2));
