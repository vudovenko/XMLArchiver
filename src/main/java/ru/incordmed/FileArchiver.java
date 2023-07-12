package ru.incordmed;

import ru.incordmed.exceptions.IncorrectDateException;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileArchiver extends SimpleFileVisitor<Path> {

    private LocalDate fileCreationDate;
    private Path mainDirectoryWithFiles;
    private Map<Path, List<Path>> folderFileCounts;
    private String folderForDeletedFiles;

    public void calculateFolders(int year, int month) {
        this.mainDirectoryWithFiles = getPathToProgramDirectory().getParent();
        this.folderFileCounts = new HashMap<>();
        // todo удалить, когда будет добавлено полноценное удаление файлов
        folderForDeletedFiles = mainDirectoryWithFiles + File.separator + "deleted";
        try {
            setFileCreationDate(year, month);
            System.out.println("\nНачало обхода файловой системы...");
            Files.walkFileTree(mainDirectoryWithFiles, this);
            System.out.println("\nОбход файловой системы завершен.");
        } catch (IncorrectDateException | IOException e) {
            e.printStackTrace();
        }
    }

    public Path getPathToProgramDirectory() {
        return Path.of(System.getProperty("user.dir"));
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (dir.getFileName()
                .equals(getPathToProgramDirectory().getFileName())
                || dir.getFileName().toString().equals("deleted")) {
            return FileVisitResult.SKIP_SUBTREE;
        } // todo убрать упоминание папки deleted в будущем
        Path path = mainDirectoryWithFiles.relativize(dir);
        int depth = path.getNameCount();
        if (depth == 1) {
            System.out.println("\nНазвание папки: " + dir.getFileName());
        } else if (depth == 2) {
            System.out.println("\nНомер папки: " + dir.getFileName());
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        System.out.println("\nФайл: " + file.getFileName()
                + "\nДата создания: " + attrs.creationTime()
                + "\nРодительская директория: " + file.getParent());
        if (isFileBeforeCreationDate(attrs)) {
            System.out.println("Файл " + file.getFileName() + " будет добавлен в архив");
            increaseNumberFilesInFolder(file);
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
            throws IOException {
        int depth = mainDirectoryWithFiles.relativize(dir).getNameCount();
        if (depth == 2) {
            int numberFiles = folderFileCounts.get(dir) != null
                    ? folderFileCounts.get(dir).size()
                    : 0;
            System.out.println("\nДиректория: " + dir
                    + "\nКоличество файлов для архивации: " + numberFiles);
            if (numberFiles != 0) {
                addToArchive(dir);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    private void addToArchive(Path dir) throws IOException {
        Path pathDeletedFolders = getStorageFolderPath(dir, folderForDeletedFiles);

        Path archiveName = Paths.get(dir + File.separator
                + dir.getFileName() + ".zip");
        System.out.println("\nСоздание архива для файлов " + archiveName);
        try (ZipOutputStream zipOutputStream
                     = new ZipOutputStream(
                Files.newOutputStream(archiveName))) {
            Path pathDeletedFolders1 = Paths.get(pathDeletedFolders
                    + File.separator + dir.getFileName());
            if (!Files.exists(pathDeletedFolders1)) {
                System.out.println("Создание директории " +
                        "для удаленных файлов " + pathDeletedFolders1);
                Files.createDirectory(pathDeletedFolders1);
            }
            addingFilesToZip(dir, pathDeletedFolders, zipOutputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addingFilesToZip(Path dir, Path pathDeletedFolders,
                                  ZipOutputStream zipOutputStream)
            throws IOException {
        for (Path fileToZip : folderFileCounts.get(dir)) {
            ZipEntry zipEntry = new ZipEntry(fileToZip
                    .getFileName().toString());
            System.out.println("Запись файла " + fileToZip
                    + " в архив");
            zipOutputStream.putNextEntry(zipEntry);
            Files.copy(fileToZip, zipOutputStream); // Добавляем в архив

            System.out.println("Перенос файла " + fileToZip
                    + " в папку для удаленных файлов");
            Files.move(fileToZip, Paths.get(pathDeletedFolders + File.separator
                    + dir.getFileName() + File.separator + fileToZip.getFileName()));
        }
    }

    private Path getStorageFolderPath(Path dir, String folder) throws IOException {
        Path archivePath = Paths.get(folder + File.separator
                + dir.getParent().getFileName());
        Files.createDirectories(archivePath);
        return archivePath;
    }

    private boolean isFileBeforeCreationDate(BasicFileAttributes attrs) {
        LocalDate creationTime = attrs.creationTime()
                .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return creationTime.isBefore(fileCreationDate);
    }

    private void setFileCreationDate(int year, int month) throws IncorrectDateException {
        if (year < 0) {
            throw new IncorrectDateException("Некорректный год: " + year);
        } else if (month < 1 || month > 12) {
            throw new IncorrectDateException("Некорректный месяц: " + month);
        }
        this.fileCreationDate = LocalDate.of(year, month, 1);
        System.out.println("Поиск по дате создания файла: " + fileCreationDate);
    }

    private void increaseNumberFilesInFolder(Path folderPath) {
        if (folderFileCounts.get(folderPath.getParent()) == null) {
            List<Path> paths = new ArrayList<>();
            paths.add(folderPath);
            folderFileCounts.put(folderPath.getParent(), paths);
        } else {
            folderFileCounts.get(folderPath.getParent()).add(folderPath);
        }
    }
}
