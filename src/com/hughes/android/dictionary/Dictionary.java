package com.hughes.android.dictionary;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hughes.util.CachingList;
import com.hughes.util.raf.FileList;
import com.hughes.util.raf.RAFFactory;
import com.hughes.util.raf.RAFSerializable;
import com.hughes.util.raf.RAFSerializableSerializer;
import com.hughes.util.raf.RAFSerializer;
import com.hughes.util.raf.UniformFileList;

public final class Dictionary implements RAFSerializable<Dictionary> {

  static final RAFSerializer<Entry> ENTRY_SERIALIZER = new RAFSerializableSerializer<Entry>(
      Entry.RAF_FACTORY);
  static final RAFSerializer<Row> ROW_SERIALIZER = new RAFSerializableSerializer<Row>(
      Row.RAF_FACTORY);
  static final RAFSerializer<IndexEntry> INDEX_ENTRY_SERIALIZER = new RAFSerializableSerializer<IndexEntry>(
      IndexEntry.RAF_FACTORY);

  final String dictionaryInfo;
  final List<Entry> entries;
  final LanguageData[] languageDatas = new LanguageData[2];

  public Dictionary(final String dictionaryInfo, final Language language0, final Language language1) {
    this.dictionaryInfo = dictionaryInfo;
    languageDatas[0] = new LanguageData(this, language0, Entry.LANG1);
    languageDatas[1] = new LanguageData(this, language1, Entry.LANG2);
    entries = new ArrayList<Entry>();
  }

  public Dictionary(final RandomAccessFile raf) throws IOException {
    dictionaryInfo = raf.readUTF();
    entries = CachingList.create(FileList.create(raf, ENTRY_SERIALIZER, raf
        .getFilePointer()), 10000);
    languageDatas[0] = new LanguageData(this, raf, Entry.LANG1);
    languageDatas[1] = new LanguageData(this, raf, Entry.LANG2);
  }

  public void write(RandomAccessFile raf) throws IOException {
    raf.writeUTF(dictionaryInfo);
    FileList.write(raf, entries, ENTRY_SERIALIZER);
    languageDatas[0].write(raf);
    languageDatas[1].write(raf);
  }

  final class LanguageData implements RAFSerializable<LanguageData> {
    final Dictionary dictionary;
    final Language language;
    final byte lang;
    final List<Row> rows;
    final List<IndexEntry> sortedIndex;

    LanguageData(final Dictionary dictionary, final Language language, final byte lang) {
      this.dictionary = dictionary;
      this.language = language;
      this.lang = lang;
      rows = new ArrayList<Row>();
      sortedIndex = new ArrayList<IndexEntry>();
    }

    LanguageData(final Dictionary dictionary, final RandomAccessFile raf, final byte lang) throws IOException {
      this.dictionary = dictionary;
      language = Language.lookup(raf.readUTF());
      if (language == null) {
        throw new RuntimeException("Unknown language.");
      }
      this.lang = lang;
      rows = CachingList.create(UniformFileList.create(raf, ROW_SERIALIZER, raf
          .getFilePointer()), 10000);
      sortedIndex = CachingList.create(FileList.create(raf,
          INDEX_ENTRY_SERIALIZER, raf.getFilePointer()), 10000);
    }

    public void write(final RandomAccessFile raf) throws IOException {
      raf.writeUTF(language.symbol);
      UniformFileList.write(raf, rows, ROW_SERIALIZER, 4);
      FileList.write(raf, sortedIndex, INDEX_ENTRY_SERIALIZER);
    }

    String rowToString(final Row row) {
      return row.isToken() ? sortedIndex.get(row.getIndex()).word : entries
          .get(row.getIndex()).toString();
    }

    int lookup(String word, final AtomicBoolean interrupted) {
      word = word.toLowerCase();

      int start = 0;
      int end = sortedIndex.size();
      while (start < end) {
        final int mid = (start + end) / 2;
        if (interrupted.get()) {
          return mid;
        }
        final IndexEntry midEntry = sortedIndex.get(mid);

        final int comp = language.sortComparator.compare(word, midEntry.word.toLowerCase());
        if (comp == 0) {
          int result = mid;
          while (result > 0 && language.findComparator.compare(word, sortedIndex.get(result - 1).word.toLowerCase()) == 0) {
            --result;
            if (interrupted.get()) {
              return result;
            }
          }
          return result;
        } else if (comp < 0) {
          end = mid;
        } else {
          start = mid + 1;
        }
      }
      return Math.min(sortedIndex.size() - 1, start);
    }

    public IndexEntry getIndexEntryForRow(final int rowIndex) {
      // TODO: this kinda blows.
      int r = rowIndex;
      Row row;
      while (true) {
        row = rows.get(r); 
        if (row.isToken() || row.indexEntry != null) {
          break;
        }
        --r;
      }
      final IndexEntry indexEntry = row.isToken() ? sortedIndex.get(row.getIndex()) : row.indexEntry;
      for (; r <= rowIndex; ++r) {
        rows.get(r).indexEntry = indexEntry;
      }
      assert false && rows.get(indexEntry.startRow).isToken();
      return indexEntry;
    }
  }

  public static final class Row implements RAFSerializable<Row> {
    final int index;

    IndexEntry indexEntry = null;

    public Row(final int index) {
      this.index = index;
    }

    static final RAFFactory<Row> RAF_FACTORY = new RAFFactory<Row>() {
      public Row create(RandomAccessFile raf) throws IOException {
        return new Row(raf.readInt());
      }
    };

    public void write(RandomAccessFile raf) throws IOException {
      raf.writeInt(index);
    }

    boolean isToken() {
      return index < 0;
    }

    public int getIndex() {
      if (index >= 0) {
        return index;
      }
      return -index - 1;
    }
  }

  public static final class IndexEntry implements RAFSerializable<IndexEntry> {
    final String word;
    final int startRow;

    public IndexEntry(final String word, final int startRow) {
      this.word = word;
      this.startRow = startRow;
    }

    static final RAFFactory<IndexEntry> RAF_FACTORY = new RAFFactory<IndexEntry>() {
      public IndexEntry create(RandomAccessFile raf) throws IOException {
        final String word = raf.readUTF();
        final int startRow = raf.readInt();
        return new IndexEntry(word, startRow);
      }
    };

    public void write(final RandomAccessFile raf) throws IOException {
      raf.writeUTF(word);
      raf.writeInt(startRow);
    }

    @Override
    public String toString() {
      return word + "@" + startRow;
    }

  }

}
