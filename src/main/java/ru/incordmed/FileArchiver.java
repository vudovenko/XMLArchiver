package ru.incordmed;

import ru.incordmed.exceptions.IncorrectDateException;
import ru.incordmed.exceptions.MissingPropertiesFileException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileArchiver extends SimpleFileVisitor<Path> {

    private LocalDate fileCreationDate;
    private Path mainDirectoryWithFiles;
    private Map<Path, List<Path>> folderFileCounts;
    private Set<String> foldersWithoutStructure;

    public void calculateFolders() {
        this.mainDirectoryWithFiles = getPathToProgramDirectory();
        this.folderFileCounts = new HashMap<>();
        try {
            int semdMonthString = Integer.parseInt(readFromProperties("semd.month"));
            System.out.println("\nПолучили месяц создания файла: " + semdMonthString);
            setFileCreationDate(LocalDate.now().getYear(), semdMonthString);

            foldersWithoutStructure = getFoldersWithoutStructure();
            System.out.println("\nПолучен список папок без структуры: " + foldersWithoutStructure);

            System.out.println("\nНачало обхода файловой системы...");
            Files.walkFileTree(mainDirectoryWithFiles, this);
            System.out.println("\nОбход файловой системы завершен.");
        } catch (IncorrectDateException | IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public Path getPathToProgramDirectory() {
        return Paths.get(System.getProperty("user.dir"));
    }

    private String readFromProperties(String propertyValue) throws MissingPropertiesFileException {
        Properties properties = new Properties();
        String pathToPropertiesFile = mainDirectoryWithFiles + File.separator + "vimis_archive.properties";
        try (InputStream inputStream = Files.newInputStream(Paths.get(pathToPropertiesFile))) {
            properties.load(inputStream);
            System.out.println("\nФайл vimis_archive.properties найден." +
                    "\nЕго путь: " + pathToPropertiesFile);
            return properties.getProperty(propertyValue);

        } catch (IOException e) {
            throw new MissingPropertiesFileException(
                    String.format("\nФайл vimis_archive.properties по пути %s не найден!", pathToPropertiesFile));
        }
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

    private static void deleteFile(Path fileToZip) throws IOException {
        System.out.println("Удаление файла " + fileToZip);
        Files.delete(fileToZip);
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        int depth = getDepth(dir);
        if (depth == 1) {
            System.out.println("\n** Название папки: " + dir.getFileName() + " **");
        } else if (depth == 2) {
            System.out.println("\n* Номер папки: " + dir.getFileName() + " *");
        }
        return FileVisitResult.CONTINUE;
    }

    private int getDepth(Path dir) {
        return mainDirectoryWithFiles.relativize(dir).getNameCount();
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (getDepth(file) == 3
                || foldersWithoutStructure
                .contains(file.getParent().getFileName().toString())) {
            System.out.println("\nФайл: " + file.getFileName()
                    + "\nДата создания: " + attrs.creationTime()
                    + "\nРодительская директория: " + file.getParent());
            if (isFileBeforeCreationDate(attrs)) {
                System.out.println("Файл " + file.getFileName()
                        + " будет добавлен в архив");
                increaseNumberFilesInFolder(file);
            }
        }

        return FileVisitResult.CONTINUE;
    }

    private boolean isFileBeforeCreationDate(BasicFileAttributes attrs) {
        LocalDate creationTime = attrs.creationTime()
                .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return creationTime.isBefore(fileCreationDate);
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

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
        if (getDepth(dir) == 2
                || foldersWithoutStructure
                .contains(dir.getFileName().toString())) {
            int numberFiles = folderFileCounts.get(dir) != null
                    ? folderFileCounts.get(dir).size()
                    : 0;
            System.out.println("\nДиректория: " + dir
                    + "\nКоличество файлов для архивации: " + numberFiles);
            if (numberFiles != 0) {
                addDirToArchive(dir);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    private void addDirToArchive(Path dir) {
        Path archiveName = Paths.get(dir + File.separator
                + getArchiveName(dir));
        System.out.println("\nСоздание архива для файлов: " + archiveName);
        try (ZipOutputStream zipOutputStream
                     = new ZipOutputStream(Files.newOutputStream(archiveName))) {
            addingFilesToZip(dir, zipOutputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private HashSet<String> getFoldersWithoutStructure() throws MissingPropertiesFileException {
        return new HashSet<>(Arrays.asList(
                readFromProperties("semd.folders_without_structure")
                        .split(",")));
    }

    private void addingFilesToZip(Path dir, ZipOutputStream zipOutputStream)
            throws IOException {
        for (Path fileToZip : folderFileCounts.get(dir)) {
            // Получение оригинальных атрибутов файла
            BasicFileAttributes attrs = Files.readAttributes(fileToZip, BasicFileAttributes.class);

            ZipEntry zipEntry = new ZipEntry(fileToZip
                    .getFileName().toString());
            // Установка оригинальных атрибутов для ZipEntry
            zipEntry.setCreationTime(attrs.creationTime());
            zipEntry.setLastModifiedTime(attrs.lastModifiedTime());
            zipEntry.setLastAccessTime(attrs.lastAccessTime());

            System.out.println("Запись файла " + fileToZip
                    + " в архив");
            zipOutputStream.putNextEntry(zipEntry);
            zipOutputStream.write(Files.readAllBytes(fileToZip)); // Добавляем в архив
            zipOutputStream.closeEntry();

            deleteFile(fileToZip);
        }
    }

    private String getArchiveName(Path dir) {
        /*
            Пример, СЭМДы номер 1 за июнь 2023:
            2023_06_1_OutputQueryLost.zip
        */
        return String.format("%d_%02d_%s_%s.zip",
                fileCreationDate.getYear(),
                fileCreationDate.getMonthValue(),
                dir.getFileName().toString(),
                dir.getParent().getFileName().toString());
    }
}
