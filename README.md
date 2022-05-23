# Pipeline Groovy Libraries

When you have multiple Pipeline jobs, you often want to share some parts of the Pipeline scripts between them to keep Pipeline scripts [DRY](http://en.wikipedia.org/wiki/Don't_repeat_yourself).
A very common use case is that you have many projects that are built in the similar way.

This plugin adds that functionality by allowing you to create “library script” SCM repositories.
You may define libraries hosted by any SCM in a location of your choice.

Comprehensive user documentation can be found [in the Pipeline chapter of the User Handbook](https://jenkins.io/doc/book/pipeline/shared-libraries/).
