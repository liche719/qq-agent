import io

p = r'C:\Users\33721\Desktop\wechat-agent\qq-agent\src\main\java\com\liche\qqagent\memory\MemoryExtractor.java'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''            Memory memory = new Memory();
            memory.setUserId(userId);
            memory.setKind("STATE");
            memory.setContent(state.length() > 800 ? state.substring(0, 800) : state);
            memory.setKeywords("covers_to:" + coversTo);
            memory.setUpdatedAt(java.time.LocalDateTime.now());
            memory.setCreatedAt(java.time.LocalDateTime.now());
            memoryRepository.save(memory);''',
'''            // 走 MemoryService 的写入路径（它负责变更日志、向量等副作用）
            long id = memoryService.create(userId, "STATE",
                    state.length() > 800 ? state.substring(0, 800) : state,
                    "covers_to:" + coversTo, 5);''', 1)

s = s.replace('            log.info("写入状态摘要 user={} covers_to={} 字数={}", userId, coversTo, memory.getContent().length());',
              '            log.info("写入状态摘要 user={} id={} covers_to={} 字数={}", userId, id, coversTo,',
              1)
s = s.replace('''              1)
        } catch (RuntimeException exception) {''', '''                    state.length());
        } catch (RuntimeException exception) {''', 1)
io.open(p, 'w', encoding='utf-8').write(s)
print('state uses MemoryService.create')
