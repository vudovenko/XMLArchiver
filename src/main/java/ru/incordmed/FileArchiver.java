package ru.incordmed;

import ru.incordmed.exceptions.IncorrectDateException;
import ru.incordmed.exceptions.MissingMonthInPropertiesException;
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
    private Set<String> foldersWithStructure;
    private String pathToPropertiesFile;
    private Long totalNumberFiles = 0L;

    public void calculateFolders() {
        this.mainDirectoryWithFiles = getPathToDirectoryForArchiving();
        this.pathToPropertiesFile = getPathToProjectFolder() + File.separator + "vimis_archive.properties";
        this.folderFileCounts = new HashMap<>();
        try {
            int semdMonthString = Integer.parseInt(getMonthFromProperties());
            System.out.println("\nПолучили месяц создания файла: " + semdMonthString);
            setFileCreationDate(LocalDate.now().getYear(), semdMonthString);

            foldersWithStructure = getPropertyValues("semd.with-structure");
            System.out.println("\nПолучен список папок со структурой: " + foldersWithStructure);

            foldersWithoutStructure = getPropertyValues("semd.without-structure");
            System.out.println("\nПолучен список папок без структуры: " + foldersWithoutStructure);

            System.out.println("\nНачало обхода файловой системы...");
            Files.walkFileTree(mainDirectoryWithFiles, this);
            System.out.println("\nПоиск файлов по дате создания до " + fileCreationDate + " завершен." +
                    "\nВсего файлов добавлено в архивы: " + totalNumberFiles);
        } catch (IncorrectDateException | IOException | MissingMonthInPropertiesException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private Path getPathToDirectoryForArchiving() {
        return getPathToProjectFolder().getParent();
    }

    private Path getPathToProjectFolder() {
        return Paths.get(System.getProperty("user.dir"));
    }

    private String getMonthFromProperties()
            throws MissingPropertiesFileException, MissingMonthInPropertiesException {
        String parameter = "semd.month";
        Optional<String> month = getPropertyValues(parameter)
                .stream()
                .findFirst();
        if (month.isPresent()) {
            return month.get();
        }
        throw new MissingMonthInPropertiesException(
                "Отсутствует месяц в файле vimis_archive.properties");
    }

    private void setFileCreationDate(int year, int month) throws IncorrectDateException {
        if (year < 0) {
            throw new IncorrectDateException("Некорректный год: " + year);
        } else if (month < 1 || month > 12) {
            throw new IncorrectDateException("Некорректный месяц: " + month);
        }
        if (month == 12) {
            fileCreationDate = LocalDate.of(year + 1, 1, 1);
        } else {
            fileCreationDate = LocalDate.of(year, month + 1, 1);
        }
        System.out.println("Поиск файлов по дате создания до: " + fileCreationDate);
    }

    private Set<String> getPropertyValues(String propertyName) throws MissingPropertiesFileException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(Paths.get(pathToPropertiesFile))) {
            properties.load(inputStream);
            return getListValues(properties, propertyName);
        } catch (IOException e) {
            throw new MissingPropertiesFileException(
                    String.format("\nФайл vimis_archive.properties по пути %s не найден!",
                            pathToPropertiesFile));
        }
    }

    private static Set<String> getListValues(Properties properties, String propertyName) {
        Set<String> propertyValues = new HashSet<>();
        for (String propertyValue : properties.stringPropertyNames()) {
            if (propertyValue.contains(propertyName)) {
                propertyValues.add(properties.getProperty(propertyValue));
            }
        }
        return propertyValues;
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
        if ((getDepth(file) == 3 && foldersWithStructure
                .contains(file.getParent().getParent().getFileName().toString()))
                || foldersWithoutStructure
                .contains(file.getParent().getFileName().toString())) {
            System.out.println("\nФайл: " + file.getFileName()
                    + "\nДата создания: " + attrs.creationTime()
                    + "\nРодительская директория: " + file.getParent());
            if (isFileBeforeCreationDate(attrs)
                    && !file.getFileName().toString().contains(".zip")) {
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
        if (getDepth(dir) == 2 && foldersWithStructure
                .contains(dir.getParent().getFileName().toString())
                || foldersWithoutStructure
                .contains(dir.getFileName().toString())) {
            int numberFiles = folderFileCounts.get(dir) != null
                    ? folderFileCounts.get(dir).size()
                    : 0;
            System.out.println("\nДиректория: " + dir
                    + "\nКоличество файлов для архивации: " + numberFiles);
            if (numberFiles != 0) {
                totalNumberFiles += numberFiles;
                addDirToArchive(dir);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    private void addDirToArchive(Path dir) {
        Path archiveName = getArchiveName(dir);
        System.out.println("\nСоздание архива для файлов: " + archiveName);
        try (ZipOutputStream zipOutputStream
                     = new ZipOutputStream(Files.newOutputStream(archiveName))) {
            addingFilesToZip(dir, zipOutputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path getArchiveName(Path dir) {
        /*
            Пример, СЭМДы номер 1 за июнь 2023:
            2023_06_1_OutputQueryLost.zip
        */
        String archiveName = getCorrectDate(dir);
        Path archivePath = Paths.get(dir + File.separator + archiveName);
        System.out.println("Имя архива: " + archivePath.getFileName());
        while (Files.exists(archivePath)) {
            System.out.println("Такой архив существует: " + archivePath.getFileName());
            archivePath = Paths.get(dir + File.separator
                    + archivePath.getFileName().toString().replace(".zip", "_1.zip"));
            System.out.println("Новое имя для архива: " + archivePath.getFileName());
        }
        return archivePath;
    }

    private String getCorrectDate(Path dir) {
        if (fileCreationDate.getMonthValue() == 1) {
            return String.format("%d_%02d_%s_%s.zip",
                    fileCreationDate.getYear() - 1,
                    12,
                    dir.getFileName().toString(),
                    dir.getParent().getFileName().toString());
        }
        return String.format("%d_%02d_%s_%s.zip",
                fileCreationDate.getYear(),
                fileCreationDate.getMonthValue() - 1,
                dir.getFileName().toString(),
                dir.getParent().getFileName().toString());
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

    private static void deleteFile(Path fileToZip) throws IOException {
        System.out.println("Удаление файла " + fileToZip);
        Files.delete(fileToZip);
    }
}
